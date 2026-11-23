/*
 * Copyright (c) 2026 Consensys Incorporated. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.statetransition.execution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToLongFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.logic.versions.gloas.helpers.MiscHelpersGloas;

/**
 * Holds execution payload envelopes which arrived before their beacon block was imported.
 *
 * <p>An envelope is not signed, so nothing about it can be checked until its block is known. Until
 * then it is held as unchecked: anyone can send such an envelope for any block root, so unchecked
 * envelopes are limited both in number and by their total SSZ size, and the oldest are evicted
 * first.
 *
 * <p>A block is known as soon as it passes gossip validation, which is before it is imported. From
 * then on an envelope can be compared with the block's bid: the one the bid commits to is held as
 * checked, where unchecked envelopes cannot evict it, and any other can be rejected.
 *
 * <p>Only envelopes for the current slot are held (and for the next one, to allow for clock
 * disparity), and everything older is dropped when the slot changes. An envelope whose block is
 * imported later than that is retrieved by root.
 */
class PendingExecutionPayloadPool {
  private static final Logger LOG = LogManager.getLogger();

  // gossip accepts one block per proposer and slot, so this is only ever reached by forks
  static final int MAX_VALIDATED_BLOCKS = 4;

  private final int maxUncheckedEnvelopes;
  private final long maxUncheckedBytes;
  private final ToLongFunction<ExecutionPayloadEnvelope> sizeFunction;

  private final Deque<UncheckedEnvelope> uncheckedEnvelopes = new ArrayDeque<>();
  private final Map<Bytes32, BeaconBlock> validatedBlocks = new HashMap<>();
  private final Map<Bytes32, PendingExecutionPayload> checkedEnvelopes = new HashMap<>();
  private long uncheckedBytes = 0;
  private UInt64 currentSlot = UInt64.ZERO;

  PendingExecutionPayloadPool(
      final int maxUncheckedEnvelopes,
      final long maxUncheckedBytes,
      final ToLongFunction<ExecutionPayloadEnvelope> sizeFunction) {
    this.maxUncheckedEnvelopes = maxUncheckedEnvelopes;
    this.maxUncheckedBytes = maxUncheckedBytes;
    this.sizeFunction = sizeFunction;
  }

  synchronized void onSlot(final UInt64 slot) {
    currentSlot = slot;
    uncheckedEnvelopes.removeIf(
        unchecked -> {
          final boolean expired = isOutsideHeldSlots(unchecked.envelope().getSlot());
          if (expired) {
            uncheckedBytes -= unchecked.size();
          }
          return expired;
        });
    checkedEnvelopes
        .values()
        .removeIf(pending -> isOutsideHeldSlots(pending.executionPayload().getSlot()));
    validatedBlocks.values().removeIf(block -> isOutsideHeldSlots(block.getSlot()));
  }

  /**
   * Holds the envelope until its block is imported.
   *
   * @return why the envelope cannot be the payload of its block, when the block is already known
   *     and does not commit to it. Such an envelope is not held.
   */
  Optional<String> add(final ExecutionPayloadEnvelope envelope, final UInt64 arrivalTimestamp) {
    final PendingExecutionPayload pending = new PendingExecutionPayload(envelope, arrivalTimestamp);
    final Optional<BeaconBlock> maybeValidatedBlock = addUncheckedUnlessBlockIsValidated(pending);
    if (maybeValidatedBlock.isEmpty()) {
      return Optional.empty();
    }
    // hashes the payload, so it is done without holding the lock
    final Optional<String> mismatch = findMismatch(envelope, maybeValidatedBlock.get());
    if (mismatch.isEmpty()) {
      addChecked(pending);
    }
    return mismatch;
  }

  /**
   * Records a block which passed gossip validation, so that envelopes can be compared with its bid
   * while the block is being imported.
   */
  void onBlockValidated(final BeaconBlock block) {
    if (block.getBody().getOptionalExecutionPayloadBid().isEmpty()) {
      return;
    }
    final List<PendingExecutionPayload> candidates;
    synchronized (this) {
      if (isOutsideHeldSlots(block.getSlot())
          || (validatedBlocks.size() >= MAX_VALIDATED_BLOCKS
              && !validatedBlocks.containsKey(block.getRoot()))) {
        return;
      }
      validatedBlocks.put(block.getRoot(), block);
      candidates = removeUnchecked(block.getRoot());
    }
    findCommittedEnvelope(candidates, block).ifPresent(this::addChecked);
  }

  /**
   * Removes everything held for the block, which has been imported.
   *
   * @return the envelope the block commits to, if it is held
   */
  Optional<PendingExecutionPayload> remove(final BeaconBlock block) {
    final PendingExecutionPayload checked;
    final List<PendingExecutionPayload> candidates;
    synchronized (this) {
      validatedBlocks.remove(block.getRoot());
      checked = checkedEnvelopes.remove(block.getRoot());
      candidates = removeUnchecked(block.getRoot());
    }
    return Optional.ofNullable(checked).or(() -> findCommittedEnvelope(candidates, block));
  }

  synchronized int getUncheckedCount() {
    return uncheckedEnvelopes.size();
  }

  synchronized long getUncheckedBytes() {
    return uncheckedBytes;
  }

  synchronized int getCheckedCount() {
    return checkedEnvelopes.size();
  }

  /**
   * @return the block of the envelope if it passed gossip validation, in which case the envelope
   *     has not been added
   */
  private synchronized Optional<BeaconBlock> addUncheckedUnlessBlockIsValidated(
      final PendingExecutionPayload pending) {
    final ExecutionPayloadEnvelope envelope = pending.executionPayload();
    if (isOutsideHeldSlots(envelope.getSlot())) {
      LOG.trace(
          "Not holding execution payload envelope for another slot: {}", envelope::toLogString);
      return Optional.empty();
    }
    final BeaconBlock validatedBlock = validatedBlocks.get(envelope.getBeaconBlockRoot());
    if (validatedBlock != null) {
      return Optional.of(validatedBlock);
    }
    final long size = sizeFunction.applyAsLong(envelope);
    if (size > maxUncheckedBytes) {
      return Optional.empty();
    }
    while (uncheckedEnvelopes.size() >= maxUncheckedEnvelopes
        || maxUncheckedBytes - uncheckedBytes < size) {
      final UncheckedEnvelope evicted = uncheckedEnvelopes.removeFirst();
      uncheckedBytes -= evicted.size();
      LOG.trace("Evicted pending execution payload envelope: {}", evicted.envelope()::toLogString);
    }
    uncheckedEnvelopes.addLast(new UncheckedEnvelope(pending, size));
    uncheckedBytes += size;
    return Optional.empty();
  }

  private synchronized void addChecked(final PendingExecutionPayload pending) {
    final ExecutionPayloadEnvelope envelope = pending.executionPayload();
    // the block may have been imported or its slot may have passed in the meantime
    if (validatedBlocks.containsKey(envelope.getBeaconBlockRoot())) {
      // the first to arrive is kept, as its arrival time is what the payload deadline is about
      checkedEnvelopes.putIfAbsent(envelope.getBeaconBlockRoot(), pending);
    }
  }

  private List<PendingExecutionPayload> removeUnchecked(final Bytes32 beaconBlockRoot) {
    final List<PendingExecutionPayload> removed = new ArrayList<>();
    final Iterator<UncheckedEnvelope> iterator = uncheckedEnvelopes.iterator();
    while (iterator.hasNext()) {
      final UncheckedEnvelope unchecked = iterator.next();
      if (unchecked.envelope().getBeaconBlockRoot().equals(beaconBlockRoot)) {
        iterator.remove();
        uncheckedBytes -= unchecked.size();
        removed.add(unchecked.pending());
      }
    }
    return removed;
  }

  private boolean isOutsideHeldSlots(final UInt64 slot) {
    return slot.isLessThan(currentSlot) || slot.isGreaterThan(currentSlot.increment());
  }

  private static Optional<PendingExecutionPayload> findCommittedEnvelope(
      final List<PendingExecutionPayload> candidates, final BeaconBlock block) {
    return candidates.stream()
        .filter(candidate -> findMismatch(candidate.executionPayload(), block).isEmpty())
        .findFirst();
  }

  private static Optional<String> findMismatch(
      final ExecutionPayloadEnvelope envelope, final BeaconBlock block) {
    return MiscHelpersGloas.findExecutionPayloadEnvelopeMismatch(
        envelope.getPayload(),
        envelope.getPayload().hashTreeRoot(),
        envelope.getExecutionRequests().hashTreeRoot(),
        envelope.getParentBeaconBlockRoot(),
        block);
  }

  record PendingExecutionPayload(
      ExecutionPayloadEnvelope executionPayload, UInt64 arrivalTimestamp) {}

  private record UncheckedEnvelope(PendingExecutionPayload pending, long size) {
    ExecutionPayloadEnvelope envelope() {
      return pending.executionPayload();
    }
  }
}
