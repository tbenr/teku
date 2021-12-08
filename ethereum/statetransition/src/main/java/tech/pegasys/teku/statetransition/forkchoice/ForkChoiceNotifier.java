/*
 * Copyright 2021 ConsenSys AG.
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

package tech.pegasys.teku.statetransition.forkchoice;

import static com.google.common.base.Preconditions.checkState;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.async.AsyncRunnerFactory;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.async.eventthread.AsyncRunnerEventThread;
import tech.pegasys.teku.infrastructure.async.eventthread.EventThread;
import tech.pegasys.teku.infrastructure.ssz.type.Bytes20;
import tech.pegasys.teku.infrastructure.ssz.type.Bytes8;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.protoarray.ForkChoiceStrategy;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.config.SpecConfig;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.datastructures.blocks.StateAndBlockSummary;
import tech.pegasys.teku.spec.datastructures.operations.versions.merge.BeaconPreparableProposer;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.executionengine.ExecutionEngineChannel;
import tech.pegasys.teku.spec.executionengine.ForkChoiceState;
import tech.pegasys.teku.spec.executionengine.ForkChoiceUpdatedResult;
import tech.pegasys.teku.spec.executionengine.PayloadAttributes;
import tech.pegasys.teku.storage.client.RecentChainData;

public class ForkChoiceNotifier {
  private static final long MAX_PROPOSER_SEEN_EPOCHS = 2;
  private static final Logger LOG = LogManager.getLogger();

  private final EventThread eventThread;
  private final Spec spec;
  private final ExecutionEngineChannel executionEngineChannel;
  private final RecentChainData recentChainData;

  private final Map<UInt64, ProposerInfo> proposerInfoByValidatorIndex = new HashMap<>();

  private Optional<ForkChoiceState> forkChoiceState = Optional.empty();
  private Optional<PayloadAttributes> payloadAttributes = Optional.empty();

  private Optional<ForkChoiceState> lastSentForkChoiceState = Optional.empty();
  private Optional<PayloadAttributes> lastSentPayloadAttributes = Optional.empty();
  private SafeFuture<Optional<Bytes8>> lastFuturePayloadId =
      SafeFuture.completedFuture(Optional.empty());

  private boolean inSync = false; // Assume we are not in sync at startup.

  private Optional<Bytes32> executionPayloadTerminalBlockHash = Optional.empty();

  ForkChoiceNotifier(
      final EventThread eventThread,
      final Spec spec,
      final ExecutionEngineChannel executionEngineChannel,
      final RecentChainData recentChainData) {
    this.eventThread = eventThread;
    this.spec = spec;
    this.executionEngineChannel = executionEngineChannel;
    this.recentChainData = recentChainData;
  }

  public static ForkChoiceNotifier create(
      final AsyncRunnerFactory asyncRunnerFactory,
      final Spec spec,
      final ExecutionEngineChannel executionEngineChannel,
      final RecentChainData recentChainData) {
    final AsyncRunnerEventThread eventThread =
        new AsyncRunnerEventThread("forkChoiceNotifier", asyncRunnerFactory);
    eventThread.start();
    return new ForkChoiceNotifier(eventThread, spec, executionEngineChannel, recentChainData);
  }

  public void onUpdatePreparableProposers(final Collection<BeaconPreparableProposer> proposers) {
    eventThread.execute(() -> internalUpdatePreparableProposers(proposers));
  }

  public void onForkChoiceUpdated(final ForkChoiceState forkChoiceState) {
    eventThread.execute(() -> internalForkChoiceUpdated(forkChoiceState, true));
  }

  public void onAttestationsDue(final UInt64 slot) {
    eventThread.execute(() -> internalAttestationsDue(slot));
  }

  public void onSyncingStatusChanged(final boolean inSync) {
    eventThread.execute(
        () -> {
          this.inSync = inSync;
        });
  }

  public SafeFuture<Optional<Bytes8>> getPayloadId(Bytes32 parentBeaconBlockRoot) {
    return eventThread.executeFuture(() -> internalGetPayloadId(parentBeaconBlockRoot, true));
  }

  public void onTerminalBlockReached(Bytes32 executionBlockHash) {
    eventThread.execute(() -> internalTerminalBlockReached(executionBlockHash));
  }

  private void internalTerminalBlockReached(Bytes32 executionBlockHash) {
    eventThread.checkOnEventThread();
    executionPayloadTerminalBlockHash = Optional.of(executionBlockHash);
  }

  /**
   * @param parentBeaconBlockRoot root of the beacon block the new block will be built on
   * @param allowPayloadIdOnTheFlyRetrieval safely control recursive calls
   * @return must return a Future resolving to:
   *     <p>Optional.empty() only when is safe to produce a block with an empty execution payload
   *     (after the merge fork and before Terminal Block arrival)
   *     <p>Optional.of(payloadId) when one of the following: 1. builds on top of execution head of
   *     parentBeaconBlockRoot 2. builds on top of the terminal block
   *     <p>in all other cases it must Throw to avoid block production
   */
  private SafeFuture<Optional<Bytes8>> internalGetPayloadId(
      final Bytes32 parentBeaconBlockRoot, final boolean allowPayloadIdOnTheFlyRetrieval) {
    eventThread.checkOnEventThread();

    final Bytes32 parentExecutionHash =
        recentChainData
            .getExecutionBlockHashForBlockRoot(parentBeaconBlockRoot)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Failed to retrieve execution payload hash from beacon block root"));

    final boolean lastForkChoiceStateCorrectlyBuildsOnTop =
        lastSentForkChoiceState
            .map(ForkChoiceState::getHeadBlockHash)
            .map(
                fcsHead -> {
                  // post-merge block
                  if (!parentExecutionHash.isZero() && fcsHead.equals(parentExecutionHash)) {
                    return true;
                  }

                  // merge block
                  return parentExecutionHash.isZero()
                      && executionPayloadTerminalBlockHash.isPresent()
                      && fcsHead.equals(executionPayloadTerminalBlockHash.get());
                })
            .orElse(false);

    if (lastForkChoiceStateCorrectlyBuildsOnTop) {
      // current Future Payload ID builds on the correct block

      return lastFuturePayloadId.thenApply(
          payloadId -> {

            // at this stage we expect payloadId to be present
            if (payloadId.isPresent()) {
              return payloadId;
            }

            throw new IllegalStateException(
                String.format(
                    "PayloadId not available for Beacon Block Root %s", parentBeaconBlockRoot));
          });
    }

    // we have no SentForkChoiceState or doesn't build on top of the right block
    if (parentExecutionHash.isZero()) {
      // pre-merge

      if (executionPayloadTerminalBlockHash.isEmpty()) {
        // we are in pre-merge and terminal block is not reached, we can build a block with empty
        // payload
        return SafeFuture.completedFuture(Optional.empty());
      }

      if (allowPayloadIdOnTheFlyRetrieval) {
        // we are in pre-merge and terminal block is reached
        // try to obtain a payloadId now
        final Bytes32 terminalBlockHash = executionPayloadTerminalBlockHash.get();
        return requestPayloadId(terminalBlockHash, Bytes32.ZERO, parentBeaconBlockRoot);
      }
    }

    // Merge is complete, so we must have a real payloadId, but we don't have one that matches

    if (allowPayloadIdOnTheFlyRetrieval) {
      // try to obtain a payloadId now
      Bytes32 finalizedExecutionBlockHash;

      finalizedExecutionBlockHash =
          lastSentForkChoiceState
              .map(ForkChoiceState::getFinalizedBlockHash)
              .orElseGet(this::retrieveFinalizedExecutionBlockHash);

      return requestPayloadId(
          parentExecutionHash, finalizedExecutionBlockHash, parentBeaconBlockRoot);
    }

    throw new IllegalStateException(
        String.format("PayloadId not available for Beacon Block Root %s", parentBeaconBlockRoot));
  }

  private SafeFuture<Optional<Bytes8>> requestPayloadId(
      Bytes32 parentExecutionBlockHash,
      Bytes32 finalizedExecutionBlockHash,
      Bytes32 parentBeaconBlockRoot) {

    final ForkChoiceState newForkChoiceState =
        new ForkChoiceState(
            parentExecutionBlockHash, parentExecutionBlockHash, finalizedExecutionBlockHash);

    internalForkChoiceUpdated(newForkChoiceState, false);
    checkState(
        lastSentForkChoiceState
            .map(lsfcState -> lsfcState.equals(newForkChoiceState))
            .orElse(false),
        "Required fork choice state was not sent");
    return internalGetPayloadId(parentBeaconBlockRoot, false);
  }

  private Bytes32 retrieveFinalizedExecutionBlockHash() {
    ForkChoiceStrategy forkChoiceStrategy = recentChainData.getForkChoiceStrategy().orElseThrow();
    final Bytes32 finalizedRoot =
        recentChainData
            .getFinalizedCheckpoint()
            .orElseThrow(() -> new IllegalStateException("Unable to obtain finalized checkpoint"))
            .getRoot();
    return forkChoiceStrategy
        .executionBlockHash(finalizedRoot)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Unable to get finalized execution Payload hash from finalized checkpoint"));
  }

  private void internalUpdatePreparableProposers(
      final Collection<BeaconPreparableProposer> proposers) {
    eventThread.checkOnEventThread();
    // Default to the genesis slot if we're pre-genesis.
    final UInt64 currentSlot = recentChainData.getCurrentSlot().orElse(SpecConfig.GENESIS_SLOT);

    // Remove expired validators
    proposerInfoByValidatorIndex.values().removeIf(info -> info.hasExpired(currentSlot));

    // Update validators
    final UInt64 expirySlot =
        currentSlot.plus(spec.getSlotsPerEpoch(currentSlot) * MAX_PROPOSER_SEEN_EPOCHS);
    for (BeaconPreparableProposer proposer : proposers) {
      proposerInfoByValidatorIndex.put(
          proposer.getValidatorIndex(), new ProposerInfo(expirySlot, proposer.getFeeRecipient()));
    }

    // Update payload attributes in case we now need to propose the next block
    updatePayloadAttributesAndCallForkChoiceUpdate(currentSlot.plus(1));
  }

  private void internalForkChoiceUpdated(
      final ForkChoiceState forkChoiceState, final boolean nextSlot) {
    eventThread.checkOnEventThread();

    if (this.forkChoiceState.isPresent() && this.forkChoiceState.get().equals(forkChoiceState)) {
      // No change required.
      return;
    }

    this.forkChoiceState = Optional.of(forkChoiceState);
    recentChainData
        .getCurrentSlot()
        .ifPresent(
            currentSlot ->
                updatePayloadAttributesAndCallForkChoiceUpdate(
                    nextSlot ? currentSlot.plus(1) : currentSlot));
  }

  private void internalAttestationsDue(final UInt64 slot) {
    eventThread.checkOnEventThread();
    // Assume `slot` is empty and check if we need to prepare to propose in the next slot
    updatePayloadAttributesAndCallForkChoiceUpdate(slot.plus(1));
  }

  private void sendForkChoiceUpdated() {
    if (lastSentForkChoiceState.equals(forkChoiceState)
        && lastSentPayloadAttributes.equals(payloadAttributes)) {
      // No change to previously sent values so no need to resend
      return;
    }
    forkChoiceState.ifPresentOrElse(
        forkChoiceState -> {
          if (forkChoiceState.getHeadBlockHash().isZero()) {
            return;
          }
          lastSentForkChoiceState = this.forkChoiceState;
          lastSentPayloadAttributes = payloadAttributes;
          // Previous payload is no longer useful as we've moved on to prepping the next block
          lastFuturePayloadId =
              executionEngineChannel
                  .forkChoiceUpdated(forkChoiceState, payloadAttributes)
                  .thenApplyAsync(
                      result -> handleForkChoiceResult(forkChoiceState, result), eventThread);
        },
        () ->
            LOG.warn(
                "Could not notify EL of fork choice update because fork choice state is not yet known"));
  }

  private void updatePayloadAttributesAndCallForkChoiceUpdate(final UInt64 blockSlot) {
    calculatePayloadAttributes(blockSlot)
        .thenAcceptAsync(
            newPayloadAttributes -> {
              updatePayloadAttributes(blockSlot, newPayloadAttributes);
              sendForkChoiceUpdated();
            },
            eventThread)
        .finish(
            error ->
                LOG.error("Failed to calculate payload attributes for slot {}", blockSlot, error));
  }

  private void updatePayloadAttributes(
      final UInt64 blockSlot, final Optional<PayloadAttributes> newPayloadAttributes) {
    eventThread.checkOnEventThread();
    if (payloadAttributes.equals(newPayloadAttributes)) {
      // No change, nothing to do.
      return;
    }
    final UInt64 currentSlot = recentChainData.getCurrentSlot().orElse(UInt64.ZERO);
    if (currentSlot.isGreaterThan(blockSlot)) {
      // Slot has already progressed so this update is too late, just drop it.
      LOG.warn(
          "Payload attribute calculation for slot {} took too long. Slot was already {}",
          blockSlot,
          currentSlot);
      return;
    }
    payloadAttributes = newPayloadAttributes;
  }

  private Optional<Bytes8> handleForkChoiceResult(
      final ForkChoiceState forkChoiceState, final ForkChoiceUpdatedResult result) {
    eventThread.checkOnEventThread();
    if (lastSentForkChoiceState.isEmpty()
        || !lastSentForkChoiceState.get().equals(forkChoiceState)) {
      // Debug level because this is quite likely to happen when syncing
      LOG.debug("Execution engine did not return payload ID in time, discarding");
      return Optional.empty();
    }
    return result.getPayloadId();
  }

  private SafeFuture<Optional<PayloadAttributes>> calculatePayloadAttributes(
      final UInt64 blockSlot) {
    eventThread.checkOnEventThread();
    if (!inSync) {
      // We don't produce blocks while syncing so don't bother preparing the payload
      return SafeFuture.completedFuture(Optional.empty());
    }
    if (forkChoiceState.isEmpty() || forkChoiceState.get().getHeadBlockHash().isZero()) {
      // No forkChoiceUpdated message will be sent so no point calculating payload attributes
      return SafeFuture.completedFuture(Optional.empty());
    }
    if (!recentChainData.isJustifiedCheckpointFullyValidated()) {
      // If we've optimistically synced far enough that our justified checkpoint is optimistic,
      // stop producing blocks because the majority of validators see the optimistic chain as valid.
      return SafeFuture.completedFuture(Optional.empty());
    }
    final UInt64 epoch = spec.computeEpochAtSlot(blockSlot);
    return getStateInEpoch(epoch)
        .thenApplyAsync(
            maybeState -> calculatePayloadAttributes(blockSlot, epoch, maybeState), eventThread);
  }

  private Optional<PayloadAttributes> calculatePayloadAttributes(
      final UInt64 blockSlot, final UInt64 epoch, final Optional<BeaconState> maybeState) {
    eventThread.checkOnEventThread();
    if (maybeState.isEmpty()) {
      return Optional.empty();
    }
    final BeaconState state = maybeState.get();
    final UInt64 proposerIndex = UInt64.valueOf(spec.getBeaconProposerIndex(state, blockSlot));
    final ProposerInfo proposerInfo = proposerInfoByValidatorIndex.get(proposerIndex);
    if (proposerInfo == null) {
      // Proposer is not one of our validators. No need to propose a block.
      return Optional.empty();
    }
    final UInt64 timestamp = spec.computeTimeAtSlot(state, blockSlot);
    final Bytes32 random = spec.getRandaoMix(state, epoch);
    return Optional.of(new PayloadAttributes(timestamp, random, proposerInfo.feeRecipient));
  }

  private SafeFuture<Optional<BeaconState>> getStateInEpoch(final UInt64 requiredEpoch) {
    final Optional<StateAndBlockSummary> chainHead = recentChainData.getChainHead();
    if (chainHead.isEmpty()) {
      return SafeFuture.completedFuture(Optional.empty());
    }
    final StateAndBlockSummary head = chainHead.get();
    if (spec.computeEpochAtSlot(head.getSlot()).equals(requiredEpoch)) {
      return SafeFuture.completedFuture(Optional.of(head.getState()));
    } else {
      return recentChainData.retrieveStateAtSlot(
          new SlotAndBlockRoot(spec.computeStartSlotAtEpoch(requiredEpoch), head.getRoot()));
    }
  }

  private static class ProposerInfo {
    UInt64 expirySlot;
    Bytes20 feeRecipient;

    public ProposerInfo(UInt64 expirySlot, Bytes20 feeRecipient) {
      this.expirySlot = expirySlot;
      this.feeRecipient = feeRecipient;
    }

    public boolean hasExpired(final UInt64 currentSlot) {
      return currentSlot.isGreaterThanOrEqualTo(expirySlot);
    }
  }
}
