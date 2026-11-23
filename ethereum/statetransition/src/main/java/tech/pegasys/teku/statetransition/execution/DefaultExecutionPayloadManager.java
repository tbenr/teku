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

import static tech.pegasys.teku.spec.config.Constants.VALID_EXECUTION_PAYLOAD_SET_SIZE;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.ethereum.events.SlotEventsChannel;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.collections.LimitedSet;
import tech.pegasys.teku.infrastructure.subscribers.Subscribers;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.BlindedExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionRequests;
import tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus;
import tech.pegasys.teku.spec.datastructures.validator.BroadcastValidationLevel;
import tech.pegasys.teku.spec.executionlayer.ExecutionLayerChannel;
import tech.pegasys.teku.spec.logic.common.statetransition.results.ExecutionPayloadImportResult;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;
import tech.pegasys.teku.statetransition.block.ReceivedBlockEventsChannel;
import tech.pegasys.teku.statetransition.forkchoice.ForkChoice;
import tech.pegasys.teku.statetransition.validation.ExecutionPayloadGossipValidator;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;
import tech.pegasys.teku.storage.client.RecentChainData;

public class DefaultExecutionPayloadManager
    implements ExecutionPayloadManager, ReceivedBlockEventsChannel, SlotEventsChannel {

  private static final Logger LOG = LogManager.getLogger();

  // Envelopes are held before anything about them can be checked, so the budget is kept small.
  // Honest nodes see about one envelope ahead of its block, and this still leaves room for several
  // envelopes of the maximum gossip size
  private static final int PENDING_EXECUTION_PAYLOADS_BYTES_MULTIPLIER = 4;
  // an envelope holds more memory than its SSZ size once decoded, which matters for small ones
  private static final int MAX_PENDING_UNCHECKED_EXECUTION_PAYLOADS = 64;

  private final Set<Bytes32> executionPayloadsSeenBeforePayloadDue =
      LimitedSet.createSynchronizedNatural(VALID_EXECUTION_PAYLOAD_SET_SIZE);

  private final PendingExecutionPayloadPool pendingExecutionPayloads;

  private final Subscribers<FailedPayloadExecutionSubscriber> failedPayloadExecutionSubscribers =
      Subscribers.create(true);

  private final Spec spec;
  private final AsyncRunner asyncRunner;
  private final ExecutionPayloadGossipValidator executionPayloadGossipValidator;
  private final ForkChoice forkChoice;
  private final ExecutionLayerChannel executionLayer;
  private final ReceivedExecutionPayloadEventsChannel
      receivedExecutionPayloadEventsChannelPublisher;
  private final RecentChainData recentChainData;
  private final Set<Bytes32> blockRootsWithInvalidExecutionPayload;
  private final Function<ExecutionPayloadEnvelope, SafeFuture<Void>> executionPayloadPublisher;

  public DefaultExecutionPayloadManager(
      final Spec spec,
      final AsyncRunner asyncRunner,
      final ExecutionPayloadGossipValidator executionPayloadGossipValidator,
      final ForkChoice forkChoice,
      final ExecutionLayerChannel executionLayer,
      final ReceivedExecutionPayloadEventsChannel receivedExecutionPayloadEventsChannelPublisher,
      final RecentChainData recentChainData,
      final Set<Bytes32> blockRootsWithInvalidExecutionPayload,
      final Function<ExecutionPayloadEnvelope, SafeFuture<Void>> executionPayloadPublisher) {
    this.spec = spec;
    this.asyncRunner = asyncRunner;
    this.executionPayloadGossipValidator = executionPayloadGossipValidator;
    this.forkChoice = forkChoice;
    this.executionLayer = executionLayer;
    this.receivedExecutionPayloadEventsChannelPublisher =
        receivedExecutionPayloadEventsChannelPublisher;
    this.recentChainData = recentChainData;
    this.blockRootsWithInvalidExecutionPayload = blockRootsWithInvalidExecutionPayload;
    this.executionPayloadPublisher = executionPayloadPublisher;
    this.pendingExecutionPayloads =
        new PendingExecutionPayloadPool(
            MAX_PENDING_UNCHECKED_EXECUTION_PAYLOADS,
            (long) PENDING_EXECUTION_PAYLOADS_BYTES_MULTIPLIER
                * spec.getNetworkingConfig().getMaxPayloadSize(),
            envelope -> envelope.getSchema().getSszSize(envelope.getBackingNode()));
  }

  @Override
  public boolean isExecutionPayloadRecentlySeen(final Bytes32 beaconBlockRoot) {
    return executionPayloadGossipValidator.isPayloadSeen(beaconBlockRoot);
  }

  @Override
  public boolean isExecutionPayloadSeenBeforeDeadline(final Bytes32 beaconBlockRoot) {
    return executionPayloadsSeenBeforePayloadDue.contains(beaconBlockRoot);
  }

  @Override
  public SafeFuture<InternalValidationResult> validateAndImportExecutionPayload(
      final ExecutionPayloadEnvelope signedExecutionPayload) {
    return validateAndImportExecutionPayload(
        signedExecutionPayload, Optional.empty(), Optional.empty());
  }

  @Override
  public SafeFuture<InternalValidationResult> validateAndImportExecutionPayload(
      final ExecutionPayloadEnvelope signedExecutionPayload,
      final Optional<UInt64> maybeArrivalTimestamp,
      final Optional<BroadcastValidationLevel> broadcastValidationLevel) {
    final UInt64 arrivalTimestamp =
        maybeArrivalTimestamp.orElseGet(() -> recentChainData.getStore().getTimeInMillis());
    return executionPayloadGossipValidator
        .validate(signedExecutionPayload, broadcastValidationLevel)
        .thenApply(
            result -> {
              switch (result.code()) {
                case ACCEPT -> {
                  receivedExecutionPayloadEventsChannelPublisher.onExecutionPayloadValidated(
                      signedExecutionPayload);
                  recordIfExecutionPayloadIsSeenBeforeDeadline(
                      signedExecutionPayload, arrivalTimestamp);
                  importExecutionPayload(signedExecutionPayload, true).finishError(LOG);
                }
                case SAVE_FOR_FUTURE -> {
                  return saveForFuture(
                      signedExecutionPayload, arrivalTimestamp, broadcastValidationLevel);
                }
                case IGNORE, REJECT -> {}
              }
              return result;
            });
  }

  @Override
  public SafeFuture<ValidateAndImportResult> validateAndImportExecutionPayloadForBroadcast(
      final ExecutionPayloadEnvelope signedExecutionPayload,
      final Optional<BroadcastValidationLevel> broadcastValidationLevel) {
    final UInt64 arrivalTimestamp = recentChainData.getStore().getTimeInMillis();
    return executionPayloadGossipValidator
        .validate(signedExecutionPayload, broadcastValidationLevel)
        .thenApply(
            result -> {
              switch (result.code()) {
                case ACCEPT -> {
                  receivedExecutionPayloadEventsChannelPublisher.onExecutionPayloadValidated(
                      signedExecutionPayload);
                  recordIfExecutionPayloadIsSeenBeforeDeadline(
                      signedExecutionPayload, arrivalTimestamp);
                  return new ValidateAndImportResult(
                      result, Optional.of(importExecutionPayload(signedExecutionPayload, true)));
                }
                case SAVE_FOR_FUTURE -> {
                  return new ValidateAndImportResult(
                      saveForFuture(
                          signedExecutionPayload, arrivalTimestamp, broadcastValidationLevel),
                      Optional.empty());
                }
                case IGNORE, REJECT -> {}
              }
              return new ValidateAndImportResult(result, Optional.empty());
            });
  }

  /**
   * Holds an envelope which cannot be validated yet until it can.
   *
   * @return the validation result, which is a rejection if the block is already known to commit to
   *     another envelope
   */
  private InternalValidationResult saveForFuture(
      final ExecutionPayloadEnvelope signedExecutionPayload,
      final UInt64 arrivalTimestamp,
      final Optional<BroadcastValidationLevel> broadcastValidationLevel) {
    if (recentChainData.containsBlock(signedExecutionPayload.getBeaconBlockRoot())) {
      // handles edge case where block was imported while validating the payload
      asyncRunner
          .runAfterDelay(
              () ->
                  validateAndImportExecutionPayload(
                          signedExecutionPayload,
                          Optional.of(arrivalTimestamp),
                          broadcastValidationLevel)
                      .thenCompose(r -> publishPayload(r, signedExecutionPayload)),
              Duration.ofMillis(100))
          .finishError(LOG);
      return InternalValidationResult.SAVE_FOR_FUTURE;
    }
    return pendingExecutionPayloads
        .add(signedExecutionPayload, arrivalTimestamp)
        .map(
            mismatch ->
                InternalValidationResult.reject(
                    "Execution payload envelope is not the one the block with root %s commits to: %s",
                    signedExecutionPayload.getBeaconBlockRoot(), mismatch))
        .orElse(InternalValidationResult.SAVE_FOR_FUTURE);
  }

  @Override
  public SafeFuture<ExecutionPayloadImportResult> importExecutionPayload(
      final ExecutionPayloadEnvelope signedExecutionPayload,
      final boolean payloadCommitmentVerified) {
    // taken before anything else: verification and the data availability check which follow can
    // take long enough to put an on time payload past its deadline
    final UInt64 arrivalTimestamp = recentChainData.getStore().getTimeInMillis();
    return asyncRunner
        .runAsync(
            () ->
                forkChoice.onExecutionPayloadEnvelope(
                    signedExecutionPayload,
                    executionLayer,
                    Optional.of(recordingSeenBeforeDeadline(arrivalTimestamp))))
        .thenPeek(
            result -> {
              if (result.isSuccessful()) {
                LOG.debug(
                    "Successfully imported execution payload {}",
                    signedExecutionPayload::toLogString);
                receivedExecutionPayloadEventsChannelPublisher.onExecutionPayloadImported(
                    signedExecutionPayload, result.isImportedOptimistically());
                return;
              }
              switch (result.getFailureReason()) {
                case FAILED_EXECUTION -> {
                  LOG.error(
                      "Unable to import execution payload {}. Execution Client returned an error: {}",
                      signedExecutionPayload::toLogString,
                      () -> result.getFailureCause().map(Throwable::getMessage).orElse(""));
                  failedPayloadExecutionSubscribers.deliver(
                      FailedPayloadExecutionSubscriber::onPayloadExecutionFailed,
                      signedExecutionPayload);
                }
                case FAILED_VERIFICATION, FAILED_DATA_AVAILABILITY_CHECK_INVALID -> {
                  if (payloadCommitmentVerified) {
                    blockRootsWithInvalidExecutionPayload.add(
                        signedExecutionPayload.getBeaconBlockRoot());
                  }
                  logFailedExecutionPayloadImport(signedExecutionPayload, result);
                }
                case INTERNAL_ERROR,
                    UNKNOWN_BEACON_BLOCK_ROOT,
                    FAILED_DATA_AVAILABILITY_CHECK_NOT_AVAILABLE ->
                    logFailedExecutionPayloadImport(signedExecutionPayload, result);
              }
            })
        .exceptionally(
            ex -> {
              final String internalErrorMessage =
                  String.format(
                      "Internal error while importing execution payload: %s. Execution payload content: %s",
                      signedExecutionPayload.toLogString(),
                      signedExecutionPayload.sszSerialize().toHexString());
              LOG.error(internalErrorMessage, ex);
              return ExecutionPayloadImportResult.internalError(ex);
            });
  }

  private void logFailedExecutionPayloadImport(
      final ExecutionPayloadEnvelope executionPayload,
      final ExecutionPayloadImportResult importResult) {
    LOG.debug(
        "Unable to import execution payload for reason {}: {}",
        importResult::toLogString,
        executionPayload::toLogString);
  }

  @Override
  public SafeFuture<ExecutionRequests> getParentExecutionRequestsForBlock(
      final UInt64 slot, final Bytes32 parentRoot, final ForkChoicePayloadStatus payloadStatus) {
    if (!payloadStatus.equals(ForkChoicePayloadStatus.PAYLOAD_STATUS_FULL)) {
      return SafeFuture.completedFuture(
          SchemaDefinitionsGloas.required(spec.atSlot(slot).getSchemaDefinitions())
              .getExecutionRequestsSchema()
              .getDefault());
    }
    // to avoid querying the EL when unblinding in some cases, we directly query for the blinded
    // execution payload which includes going over the in-memory payloads as well
    return recentChainData
        .retrieveSignedBlindedExecutionPayloadByBlockRoot(parentRoot)
        .thenApply(
            executionPayload ->
                executionPayload
                    .map(BlindedExecutionPayloadEnvelope::getExecutionRequests)
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                String.format(
                                    "Execution Requests for parent root %s are not available during block production for slot %s",
                                    parentRoot, slot))));
  }

  @Override
  public void subscribeFailedPayloadExecution(final FailedPayloadExecutionSubscriber subscriber) {
    failedPayloadExecutionSubscribers.subscribe(subscriber);
  }

  @Override
  public void onSlot(final UInt64 slot) {
    pendingExecutionPayloads.onSlot(slot);
  }

  @Override
  public void onBlockValidated(final SignedBeaconBlock block) {
    // comparing the pending envelopes with the block hashes their payloads, which must not hold
    // up the block
    asyncRunner
        .runAsync(() -> pendingExecutionPayloads.onBlockValidated(block.getMessage()))
        .finishError(LOG);
  }

  @Override
  public void onBlockImported(final SignedBeaconBlock block, final boolean executionOptimistic) {
    // Process pending execution payload
    asyncRunner
        .runAsync(() -> pendingExecutionPayloads.remove(block.getMessage()))
        .thenCompose(
            maybePendingExecutionPayload ->
                maybePendingExecutionPayload
                    .map(
                        pendingExecutionPayload ->
                            validateAndImportExecutionPayload(
                                    pendingExecutionPayload.executionPayload(),
                                    Optional.of(pendingExecutionPayload.arrivalTimestamp()))
                                .thenCompose(
                                    result ->
                                        publishPayload(
                                            result, pendingExecutionPayload.executionPayload())))
                    .orElse(SafeFuture.COMPLETE))
        .finishError(LOG);
  }

  private void recordIfExecutionPayloadIsSeenBeforeDeadline(
      final ExecutionPayloadEnvelope signedExecutionPayload, final UInt64 arrivalTimestamp) {
    if (isBeforePayloadDue(signedExecutionPayload, arrivalTimestamp)) {
      executionPayloadsSeenBeforePayloadDue.add(signedExecutionPayload.getBeaconBlockRoot());
    }
  }

  private boolean isBeforePayloadDue(
      final ExecutionPayloadEnvelope signedExecutionPayload, final UInt64 arrivalTimestamp) {
    final UInt64 slot = signedExecutionPayload.getSlot();
    // the slot comes from an envelope which may not have been verified yet, and a slot from before
    // Gloas has no payload deadline to be before
    return spec.getPayloadDueMillis(slot)
        .map(
            payloadDueMillis ->
                spec.isBeforeTimeInSlot(
                    slot,
                    recentChainData.getGenesisTimeMillis(),
                    arrivalTimestamp,
                    payloadDueMillis))
        .orElse(false);
  }

  /**
   * Reports the payload as seen, at the time it arrived, as soon as fork choice reports it
   * available, which it only does once it has verified the envelope and its data are available. A
   * payload which arrived over gossip was already recorded with its arrival time, this covers the
   * ones which did not, for example a payload fetched by root.
   */
  private ReceivedExecutionPayloadEventsChannel recordingSeenBeforeDeadline(
      final UInt64 arrivalTimestamp) {
    return new ReceivedExecutionPayloadEventsChannel() {
      @Override
      public void onExecutionPayloadValidated(final ExecutionPayloadEnvelope executionPayload) {
        receivedExecutionPayloadEventsChannelPublisher.onExecutionPayloadValidated(
            executionPayload);
      }

      @Override
      public void onExecutionPayloadAvailable(final ExecutionPayloadEnvelope executionPayload) {
        recordIfExecutionPayloadIsSeenBeforeDeadline(executionPayload, arrivalTimestamp);
        receivedExecutionPayloadEventsChannelPublisher.onExecutionPayloadAvailable(
            executionPayload);
      }

      @Override
      public void onExecutionPayloadImported(
          final ExecutionPayloadEnvelope executionPayload, final boolean executionOptimistic) {
        receivedExecutionPayloadEventsChannelPublisher.onExecutionPayloadImported(
            executionPayload, executionOptimistic);
      }
    };
  }

  // publish payload in cases where initial validation wasn't accepted
  private SafeFuture<Void> publishPayload(
      final InternalValidationResult result, final ExecutionPayloadEnvelope executionPayload) {
    if (result.isAccept()) {
      return executionPayloadPublisher.apply(executionPayload);
    }
    return SafeFuture.COMPLETE;
  }
}
