/*
 * Copyright ConsenSys Software Inc., 2023
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

package tech.pegasys.teku.statetransition.util;

import static tech.pegasys.teku.spec.config.SpecConfig.GENESIS_SLOT;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.ethereum.events.SlotEventsChannel;
import tech.pegasys.teku.infrastructure.metrics.SettableLabelledGauge;
import tech.pegasys.teku.infrastructure.subscribers.Subscribers;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.datastructures.execution.versions.deneb.BlobSidecar;
import tech.pegasys.teku.spec.datastructures.networking.libp2p.rpc.BlobIdentifier;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;
import tech.pegasys.teku.statetransition.blobs.BlobSidecarPool;
import tech.pegasys.teku.statetransition.blobs.BlockBlobSidecarsTracker;
import tech.pegasys.teku.statetransition.blobs.BlockBlobsSidecarsTrackerFactory;
import tech.pegasys.teku.storage.api.FinalizedCheckpointChannel;

public class BlobSidecarPoolImpl
    implements SlotEventsChannel, FinalizedCheckpointChannel, BlobSidecarPool {
  private static final String GAUGE_TYPE_LABEL = "blob_sidecars";

  private final SettableLabelledGauge sizeGauge;
  private final Map<Bytes32, BlockBlobSidecarsTracker> blockBlobSidecarsTrackers = new HashMap<>();
  private final NavigableSet<SlotAndBlockRoot> orderedBlobSidecarsTrackers = new TreeSet<>();
  private final Spec spec;
  private final UInt64 futureSlotTolerance;
  private final UInt64 historicalSlotTolerance;
  private final int maxItems;
  private final BlockBlobsSidecarsTrackerFactory blockBlobsSidecarsTrackerFactory;

  private final Subscribers<RequiredBlockRootSubscriber> requiredBlockRootSubscribers =
      Subscribers.create(true);
  private final Subscribers<RequiredBlockRootDroppedSubscriber>
      requiredBlockRootDroppedSubscribers = Subscribers.create(true);

  private final Subscribers<RequiredBlobSidecarSubscriber> requiredBlobSidecarSubscribers =
      Subscribers.create(true);
  private final Subscribers<RequiredBlobSidecarDroppedSubscriber>
      requiredBlobSidecarDroppedSubscribers = Subscribers.create(true);

  private int size;

  private volatile UInt64 currentSlot = UInt64.ZERO;
  private volatile UInt64 latestFinalizedSlot = GENESIS_SLOT;

  BlobSidecarPoolImpl(
      final SettableLabelledGauge sizeGauge,
      final Spec spec,
      final UInt64 historicalSlotTolerance,
      final UInt64 futureSlotTolerance,
      final int maxItems,
      final BlockBlobsSidecarsTrackerFactory blockBlobsSidecarsTrackerFactory) {
    this.spec = spec;
    this.historicalSlotTolerance = historicalSlotTolerance;
    this.futureSlotTolerance = futureSlotTolerance;
    this.maxItems = maxItems;
    this.sizeGauge = sizeGauge;
    this.blockBlobsSidecarsTrackerFactory = blockBlobsSidecarsTrackerFactory;

    sizeGauge.set(0, GAUGE_TYPE_LABEL); // Init the label so it appears in metrics immediately
  }

  @Override
  public synchronized void add(final BlobSidecar blobSidecar) {
    if (shouldIgnoreItem(blobSidecar)) {
      return;
    }

    // Make room for the new item
    while (blockBlobSidecarsTrackers.size() > (maxItems - 1)) {
      final SlotAndBlockRoot toRemove = orderedBlobSidecarsTrackers.pollFirst();
      if (toRemove == null) {
        break;
      }
      removeAllForBlock(toRemove);
    }

    final BlockBlobSidecarsTracker blobSidecars = getOrCreateBlobSidecarsTracker(blobSidecar);

    if (blobSidecars.add(blobSidecar)) {
      sizeGauge.set(++size, GAUGE_TYPE_LABEL);
    }
  }

  public synchronized void onBlock(final SignedBeaconBlock block) {
    getOrCreateBlobSidecarsTracker(block);
  }

  public synchronized void removeAllForBlock(final SlotAndBlockRoot slotAndBlockRoot) {
    orderedBlobSidecarsTrackers.remove(slotAndBlockRoot);
    final BlockBlobSidecarsTracker removed =
        blockBlobSidecarsTrackers.remove(slotAndBlockRoot.getBlockRoot());
    if (removed != null) {
      size -= removed.blobSidecarsCount();
    }
  }

  @Override
  public synchronized BlockBlobSidecarsTracker getBlockBlobsSidecarsTracker(
      final SignedBeaconBlock block) {
    return getOrCreateBlobSidecarsTracker(block);
  }

  @Override
  public void onSlot(UInt64 slot) {
    currentSlot = slot;
    if (currentSlot.mod(historicalSlotTolerance).equals(UInt64.ZERO)) {
      // Purge old items
      prune();
    }
  }

  @Override
  public void onNewFinalizedCheckpoint(Checkpoint checkpoint, boolean fromOptimisticBlock) {
    this.latestFinalizedSlot = checkpoint.getEpochStartSlot(spec);
  }

  @VisibleForTesting
  synchronized void prune() {
    final UInt64 slotLimit = latestFinalizedSlot.max(calculateItemAgeLimit());

    final List<SlotAndBlockRoot> toRemove = new ArrayList<>();
    for (SlotAndBlockRoot slotAndBlockRoot : orderedBlobSidecarsTrackers) {
      if (slotAndBlockRoot.getSlot().isGreaterThan(slotLimit)) {
        break;
      }
      toRemove.add(slotAndBlockRoot);
    }

    toRemove.forEach(this::removeAllForBlock);
  }

  @Override
  public synchronized boolean containsBlobSidecar(final BlobIdentifier blobIdentifier) {
    return Optional.ofNullable(blockBlobSidecarsTrackers.get(blobIdentifier.getBlockRoot()))
        .map(tracker -> tracker.containsBlobSidecar(blobIdentifier))
        .orElse(false);
  }

  @Override
  public synchronized Set<BlobIdentifier> getAllRequiredBlobSidecars() {
    return blockBlobSidecarsTrackers.values().stream()
        .flatMap(BlockBlobSidecarsTracker::getMissingBlobSidecars)
        .collect(Collectors.toSet());
  }

  @Override
  public void subscribeRequiredBlobSidecar(
      final RequiredBlobSidecarSubscriber requiredBlobSidecarSubscriber) {
    requiredBlobSidecarSubscribers.subscribe(requiredBlobSidecarSubscriber);
  }

  @Override
  public void subscribeRequiredBlobSidecarDropped(
      final RequiredBlobSidecarDroppedSubscriber requiredBlobSidecarDroppedSubscriber) {
    requiredBlobSidecarDroppedSubscribers.subscribe(requiredBlobSidecarDroppedSubscriber);
  }

  @Override
  public void subscribeRequiredBlockRootSubscriber(
      final RequiredBlockRootSubscriber requiredBlockRootSubscriber) {
    requiredBlockRootSubscribers.subscribe(requiredBlockRootSubscriber);
  }

  @Override
  public void subscribeRequiredBlockRootDropped(
      final RequiredBlockRootDroppedSubscriber requiredBlockRootDroppedSubscriber) {
    requiredBlockRootDroppedSubscribers.subscribe(requiredBlockRootDroppedSubscriber);
  }

  private BlockBlobSidecarsTracker getOrCreateBlobSidecarsTracker(final BlobSidecar blobSidecar) {
    return blockBlobSidecarsTrackers.computeIfAbsent(
        blobSidecar.getBlockRoot(), __ -> blockBlobsSidecarsTrackerFactory.createFrom(blobSidecar));
  }

  private BlockBlobSidecarsTracker getOrCreateBlobSidecarsTracker(final SignedBeaconBlock block) {
    return blockBlobSidecarsTrackers.computeIfAbsent(
        block.getRoot(), __ -> blockBlobsSidecarsTrackerFactory.createFrom(block));
  }

  private boolean shouldIgnoreItem(final BlobSidecar item) {
    return isTooOld(item) || isFromFarFuture(item);
  }

  private boolean isTooOld(final BlobSidecar item) {
    return isFromAFinalizedSlot(item) || isOutsideOfHistoricalLimit(item);
  }

  private boolean isFromFarFuture(final BlobSidecar blobSidecar) {
    final UInt64 slot = calculateFutureItemLimit();
    return blobSidecar.getSlot().isGreaterThan(slot);
  }

  private boolean isOutsideOfHistoricalLimit(final BlobSidecar blobSidecar) {
    final UInt64 slot = calculateItemAgeLimit();
    return blobSidecar.getSlot().compareTo(slot) <= 0;
  }

  private boolean isFromAFinalizedSlot(final BlobSidecar blobSidecar) {
    return blobSidecar.getSlot().compareTo(latestFinalizedSlot) <= 0;
  }

  private UInt64 calculateItemAgeLimit() {
    return currentSlot.compareTo(historicalSlotTolerance.plus(UInt64.ONE)) > 0
        ? currentSlot.minus(UInt64.ONE).minus(historicalSlotTolerance)
        : GENESIS_SLOT;
  }

  private UInt64 calculateFutureItemLimit() {
    return currentSlot.plus(futureSlotTolerance);
  }
}
