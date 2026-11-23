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

package tech.pegasys.teku.validator.coordinator.publisher;

import static tech.pegasys.teku.infrastructure.logging.ValidatorLogger.VALIDATOR_LOGGER;
import static tech.pegasys.teku.spec.config.SpecConfigGloas.BUILDER_INDEX_SELF_BUILD;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.gossip.DataColumnSidecarGossipChannel;
import tech.pegasys.teku.networking.eth2.gossip.ExecutionPayloadGossipChannel;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.config.SpecConfigFulu;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadBid;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelopeContents;
import tech.pegasys.teku.spec.datastructures.validator.BroadcastValidationLevel;
import tech.pegasys.teku.spec.logic.versions.gloas.helpers.MiscHelpersGloas;
import tech.pegasys.teku.statetransition.blobs.RemoteOrigin;
import tech.pegasys.teku.statetransition.block.ReceivedBlockEventsChannel;
import tech.pegasys.teku.statetransition.execution.ExecutionPayloadManager;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;
import tech.pegasys.teku.storage.client.RecentChainData;
import tech.pegasys.teku.validator.api.PublishSignedExecutionPayloadResult;
import tech.pegasys.teku.validator.coordinator.ExecutionPayloadFactory;

/**
 * Publishes execution payload envelopes with their data column sidecars: the ones submitted through
 * the API, and the one this node built itself once the block committing to it is imported.
 */
public class ExecutionPayloadPublisherGloas
    implements ExecutionPayloadPublisher, ReceivedBlockEventsChannel {

  private static final Logger LOG = LogManager.getLogger();

  // API requests waiting for the beacon block of their envelope, by block root
  private final Map<Bytes32, SafeFuture<Void>> pendingBlockArrivals = new ConcurrentHashMap<>();

  private final Spec spec;
  private final AsyncRunner asyncRunner;
  private final ExecutionPayloadFactory executionPayloadFactory;
  private final ExecutionPayloadGossipChannel executionPayloadGossipChannel;
  private final DataColumnSidecarGossipChannel dataColumnSidecarGossipChannel;
  private final ExecutionPayloadManager executionPayloadManager;
  private final RecentChainData recentChainData;

  public ExecutionPayloadPublisherGloas(
      final Spec spec,
      final AsyncRunner asyncRunner,
      final ExecutionPayloadFactory executionPayloadFactory,
      final ExecutionPayloadGossipChannel executionPayloadGossipChannel,
      final DataColumnSidecarGossipChannel dataColumnSidecarGossipChannel,
      final ExecutionPayloadManager executionPayloadManager,
      final RecentChainData recentChainData) {
    this.spec = spec;
    this.asyncRunner = asyncRunner;
    this.executionPayloadFactory = executionPayloadFactory;
    this.executionPayloadGossipChannel = executionPayloadGossipChannel;
    this.dataColumnSidecarGossipChannel = dataColumnSidecarGossipChannel;
    this.executionPayloadManager = executionPayloadManager;
    this.recentChainData = recentChainData;
  }

  /**
   * The envelope is not signed, so nothing from the request is broadcast or stored before the
   * envelope has been checked against its beacon block and the blobs and KZG proofs against the
   * blob KZG commitments of the block's execution payload bid.
   *
   * <p>A block which has not been seen is awaited until the end of the envelope's slot, as it may
   * have been published through another beacon node. An envelope the node already knows, for
   * example because it built and revealed the payload itself, is not broadcast again, but the blob
   * data of the request still is, and the result is that of the import of the known envelope.
   */
  @Override
  public SafeFuture<PublishSignedExecutionPayloadResult> publishSignedExecutionPayload(
      final ExecutionPayloadEnvelopeContents contents,
      final Optional<BroadcastValidationLevel> broadcastValidationLevel) {
    final ExecutionPayloadEnvelope executionPayload = contents.getExecutionPayloadEnvelope();
    final Bytes32 beaconBlockRoot = executionPayload.getBeaconBlockRoot();
    return awaitBlock(executionPayload)
        .thenCompose(
            maybeBlock -> {
              if (maybeBlock.isEmpty()) {
                return SafeFuture.completedFuture(
                    PublishSignedExecutionPayloadResult.blockNotFound(beaconBlockRoot));
              }
              final Optional<String> maybeMismatch = findMismatch(contents, maybeBlock.get());
              if (maybeMismatch.isPresent()) {
                return SafeFuture.completedFuture(
                    PublishSignedExecutionPayloadResult.rejected(
                        beaconBlockRoot, maybeMismatch.get()));
              }
              final ExecutionPayloadBid bid =
                  maybeBlock.get().getBody().getOptionalExecutionPayloadBid().orElseThrow();
              // computing the cells and verifying their KZG proofs is CPU heavy
              return asyncRunner
                  .runAsync(() -> createVerifiedDataColumnSidecars(contents, bid))
                  .thenCompose(
                      maybeDataColumnSidecars -> {
                        if (maybeDataColumnSidecars.isEmpty()) {
                          return SafeFuture.completedFuture(
                              PublishSignedExecutionPayloadResult.rejected(
                                  beaconBlockRoot,
                                  "Blobs and KZG proofs do not match the blob KZG commitments of"
                                      + " the execution payload bid"));
                        }
                        if (executionPayloadManager.isExecutionPayloadRecentlySeen(
                            beaconBlockRoot)) {
                          dataColumnSidecarGossipChannel.publishDataColumnSidecars(
                              maybeDataColumnSidecars.get(), RemoteOrigin.LOCAL_PROPOSAL);
                          return getResultOfKnownExecutionPayload(beaconBlockRoot);
                        }
                        return validateAndPublish(
                            executionPayload,
                            maybeDataColumnSidecars.get(),
                            broadcastValidationLevel);
                      });
            });
  }

  @Override
  public void onBlockValidated(final SignedBeaconBlock block) {}

  @Override
  public void onBlockImported(final SignedBeaconBlock block, final boolean executionOptimistic) {
    Optional.ofNullable(pendingBlockArrivals.get(block.getRoot()))
        .ifPresent(blockArrival -> blockArrival.complete(null));
    publishSelfBuiltExecutionPayload(block.getMessage());
  }

  /**
   * The execution payload envelope is not signed, so once the block of a self-built bid has been
   * imported, however it reached this node, the node reveals the payload it built for that bid.
   * Nothing is revealed when the payload was built elsewhere.
   */
  private void publishSelfBuiltExecutionPayload(final BeaconBlock block) {
    final boolean isSelfBuilt =
        block
            .getBody()
            .getOptionalExecutionPayloadBid()
            .map(bid -> bid.getBuilderIndex().equals(BUILDER_INDEX_SELF_BUILD))
            .orElse(false);
    if (!isSelfBuilt || executionPayloadManager.isExecutionPayloadRecentlySeen(block.getRoot())) {
      return;
    }
    final UInt64 slot = block.getSlot();
    SafeFuture.of(() -> executionPayloadFactory.createSelfBuiltExecutionPayload(block))
        .thenCompose(
            maybeContents -> {
              if (maybeContents.isEmpty()) {
                LOG.debug(
                    "Not publishing an execution payload for the self-built bid of block {} at slot {}: this node holds no locally built payload matching the bid",
                    block.getRoot(),
                    slot);
                return SafeFuture.COMPLETE;
              }
              final ExecutionPayloadEnvelope executionPayload =
                  maybeContents.get().getExecutionPayloadEnvelope();
              return asyncRunner
                  .runAsync(
                      () -> executionPayloadFactory.createDataColumnSidecars(maybeContents.get()))
                  .thenCompose(
                      dataColumnSidecars ->
                          validateAndPublish(
                              executionPayload,
                              dataColumnSidecars,
                              Optional.of(BroadcastValidationLevel.GOSSIP)))
                  .thenAccept(
                      result -> {
                        if (result.isPublished()) {
                          VALIDATOR_LOGGER.logExecutionPayloadDuty(
                              slot,
                              block.getRoot(),
                              executionPayload.getPayload().toDutySummary(LOG));
                        } else {
                          VALIDATOR_LOGGER.executionPayloadDutyFailed(
                              slot,
                              new IllegalArgumentException(
                                  "Execution payload was rejected: "
                                      + result.rejectionReason().orElse("<reason unknown>")));
                        }
                      });
            })
        .finish(error -> VALIDATOR_LOGGER.executionPayloadDutyFailed(slot, error));
  }

  /**
   * An envelope is known from the moment it passes gossip validation, which is before it has been
   * executed and its data checked for availability, so whether it was imported is only known once
   * its import has completed.
   */
  private SafeFuture<PublishSignedExecutionPayloadResult> getResultOfKnownExecutionPayload(
      final Bytes32 beaconBlockRoot) {
    return executionPayloadManager
        .getRecentExecutionPayloadImportFailure(beaconBlockRoot)
        .map(
            importFailure ->
                importFailure.thenApply(
                    maybeFailureReason ->
                        maybeFailureReason
                            .map(
                                failureReason ->
                                    PublishSignedExecutionPayloadResult.notImported(
                                        beaconBlockRoot, failureReason.name()))
                            .orElseGet(
                                () ->
                                    PublishSignedExecutionPayloadResult.success(beaconBlockRoot))))
        // nothing is known about the import of an envelope which was seen long enough ago
        .orElseGet(
            () ->
                SafeFuture.completedFuture(
                    PublishSignedExecutionPayloadResult.success(beaconBlockRoot)));
  }

  /**
   * The data column sidecars are only published once the envelope has passed broadcast validation:
   * no honest node adds a payload to its store without them, and they are useless without it.
   */
  private SafeFuture<PublishSignedExecutionPayloadResult> validateAndPublish(
      final ExecutionPayloadEnvelope executionPayload,
      final List<DataColumnSidecar> dataColumnSidecars,
      final Optional<BroadcastValidationLevel> broadcastValidationLevel) {
    final Bytes32 beaconBlockRoot = executionPayload.getBeaconBlockRoot();
    return executionPayloadManager
        .validateAndImportExecutionPayloadForBroadcast(executionPayload, broadcastValidationLevel)
        .thenCompose(
            validateAndImportResult -> {
              final InternalValidationResult validationResult =
                  validateAndImportResult.validationResult();
              if (!validationResult.isAccept()) {
                return SafeFuture.completedFuture(
                    PublishSignedExecutionPayloadResult.rejected(
                        beaconBlockRoot,
                        "Failed broadcast validation"
                            + validationResult
                                .getDescription()
                                .map(description -> ": " + description)
                                .orElse("")));
              }
              executionPayloadGossipChannel
                  .publishExecutionPayload(executionPayload)
                  .finishError(LOG);
              dataColumnSidecarGossipChannel.publishDataColumnSidecars(
                  dataColumnSidecars, RemoteOrigin.LOCAL_PROPOSAL);
              return validateAndImportResult
                  .importResult()
                  .orElseThrow(() -> new IllegalStateException("ACCEPT without import future"))
                  .thenApply(
                      importResult ->
                          importResult.isSuccessful()
                              ? PublishSignedExecutionPayloadResult.success(beaconBlockRoot)
                              : PublishSignedExecutionPayloadResult.notImported(
                                  beaconBlockRoot, importResult.getFailureReason().name()));
            });
  }

  /**
   * The beacon block of the envelope, waiting for it until the end of the envelope's slot when it
   * has not been seen yet. Empty when the block did not arrive, or when it is unknown and the
   * envelope is not for the current slot.
   */
  private SafeFuture<Optional<BeaconBlock>> awaitBlock(
      final ExecutionPayloadEnvelope executionPayload) {
    final Bytes32 beaconBlockRoot = executionPayload.getBeaconBlockRoot();
    return recentChainData
        .retrieveBlockByRoot(beaconBlockRoot)
        .thenCompose(
            maybeBlock -> {
              final Optional<Duration> maybeTimeLeftInSlot =
                  getTimeLeftInCurrentSlot(executionPayload.getSlot());
              if (maybeBlock.isPresent() || maybeTimeLeftInSlot.isEmpty()) {
                return SafeFuture.completedFuture(maybeBlock);
              }
              final SafeFuture<Void> blockArrival =
                  pendingBlockArrivals.computeIfAbsent(
                      beaconBlockRoot,
                      __ -> newBlockArrival(beaconBlockRoot, maybeTimeLeftInSlot.get()));
              // the block may have been imported after the lookup and before the registration
              if (recentChainData.containsBlock(beaconBlockRoot)) {
                blockArrival.complete(null);
              }
              return blockArrival
                  .handle((__, timeout) -> null)
                  .thenCompose(__ -> recentChainData.retrieveBlockByRoot(beaconBlockRoot));
            });
  }

  /** Completes when the block is imported, and fails when {@code timeout} elapses first. */
  private SafeFuture<Void> newBlockArrival(final Bytes32 beaconBlockRoot, final Duration timeout) {
    final SafeFuture<Void> blockArrival = new SafeFuture<>();
    blockArrival
        .orTimeout(asyncRunner, timeout)
        .alwaysRun(() -> pendingBlockArrivals.remove(beaconBlockRoot, blockArrival))
        .finish(__ -> LOG.debug("Block {} did not arrive by the end of its slot", beaconBlockRoot));
    return blockArrival;
  }

  private Optional<Duration> getTimeLeftInCurrentSlot(final UInt64 slot) {
    if (!recentChainData.getCurrentSlot().map(slot::equals).orElse(false)) {
      return Optional.empty();
    }
    final UInt64 endOfSlotMillis =
        spec.computeTimeMillisAtSlot(slot.plus(1), recentChainData.getGenesisTimeMillis());
    final UInt64 nowMillis = recentChainData.getStore().getTimeInMillis();
    return endOfSlotMillis.isGreaterThan(nowMillis)
        ? Optional.of(Duration.ofMillis(endOfSlotMillis.minus(nowMillis).longValue()))
        : Optional.empty();
  }

  /**
   * Whether the envelope and the amount of blob data are those the block commits to: the first
   * mismatch, or empty when they are.
   */
  private Optional<String> findMismatch(
      final ExecutionPayloadEnvelopeContents contents, final BeaconBlock block) {
    final ExecutionPayloadEnvelope executionPayload = contents.getExecutionPayloadEnvelope();
    return MiscHelpersGloas.findExecutionPayloadEnvelopeMismatch(
            executionPayload.getPayload(),
            executionPayload.getPayload().hashTreeRoot(),
            executionPayload.getExecutionRequests().hashTreeRoot(),
            executionPayload.getParentBeaconBlockRoot(),
            block)
        .or(
            () -> {
              final int blobCount =
                  block
                      .getBody()
                      .getOptionalExecutionPayloadBid()
                      .orElseThrow()
                      .getBlobKzgCommitments()
                      .size();
              final int numberOfColumns =
                  SpecConfigFulu.required(spec.atSlot(block.getSlot()).getConfig())
                      .getNumberOfColumns();
              return contents.getBlobs().size() == blobCount
                      && contents.getKzgProofs().size() == blobCount * numberOfColumns
                  ? Optional.empty()
                  : Optional.of(
                      "number of blobs and KZG proofs does not match the blob KZG commitments of"
                          + " the bid");
            });
  }

  /** The data column sidecars of the request, or empty when its blob data does not match the bid */
  private Optional<List<DataColumnSidecar>> createVerifiedDataColumnSidecars(
      final ExecutionPayloadEnvelopeContents contents, final ExecutionPayloadBid bid) {
    try {
      final List<DataColumnSidecar> dataColumnSidecars =
          executionPayloadFactory.createDataColumnSidecars(contents);
      final boolean matchesBid =
          bid.getBlobKzgCommitments().isEmpty()
              ? dataColumnSidecars.isEmpty()
              : MiscHelpersGloas.required(spec.atSlot(bid.getSlot()).miscHelpers())
                  .verifyDataColumnSidecarKzgProofsBatch(
                      dataColumnSidecars, bid.getBlobKzgCommitments());
      return matchesBid ? Optional.of(dataColumnSidecars) : Optional.empty();
    } catch (final RuntimeException e) {
      LOG.debug("Unable to verify the submitted blob data against the bid", e);
      return Optional.empty();
    }
  }
}
