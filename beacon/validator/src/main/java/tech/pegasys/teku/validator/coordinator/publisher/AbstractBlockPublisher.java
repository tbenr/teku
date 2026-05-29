/*
 * Copyright Consensys Software Inc., 2026
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
import static tech.pegasys.teku.spec.logic.common.statetransition.results.BlockImportResult.FailureReason.FAILED_BROADCAST_VALIDATION;

import com.google.common.base.Suppliers;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tech.pegasys.teku.ethereum.performance.trackers.BlockPublishingPerformance;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.gossip.BlockGossipChannel;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blobs.versions.deneb.BlobSidecar;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockContainer;
import tech.pegasys.teku.spec.datastructures.validator.BroadcastValidationLevel;
import tech.pegasys.teku.spec.logic.common.statetransition.results.BlockImportResult;
import tech.pegasys.teku.spec.logic.common.statetransition.results.BlockImportResult.FailureReason;
import tech.pegasys.teku.statetransition.block.BlockImportChannel;
import tech.pegasys.teku.statetransition.block.BlockImportChannel.BlockImportAndBroadcastValidationResults;
import tech.pegasys.teku.statetransition.validation.BlockBroadcastValidator.BroadcastValidationResult;
import tech.pegasys.teku.validator.api.SendSignedBlockResult;
import tech.pegasys.teku.validator.coordinator.BlockFactory;
import tech.pegasys.teku.validator.coordinator.DutyMetrics;

public abstract class AbstractBlockPublisher implements BlockPublisher {
  private static final Logger LOG = LogManager.getLogger();

  static final List<BiFunction<Spec, UInt64, Integer>> DELAYS_MILLIS =
      List.of(
          (spec, slot) -> spec.atSlot(slot).getForkChoiceUtil().getAttestationDueMillis(),
          (spec, slot) -> spec.atSlot(slot).getForkChoiceUtil().getAttestationDueMillis() + 1_000,
          (spec, slot) -> spec.atSlot(slot).getForkChoiceUtil().getAggregateDueMillis(),
          (spec, slot) -> spec.atSlot(slot).getConfig().getSlotDurationMillis() - 50,
          (spec, slot) -> spec.atSlot(slot).getConfig().getSlotDurationMillis(),
          (spec, slot) -> spec.atSlot(slot).getConfig().getSlotDurationMillis() + 1_500);

  static final AtomicInteger LAST_DELAY_INDEX = new AtomicInteger(0);

  private final AsyncRunner asyncRunner;

  private final Spec spec;

  private final boolean isLateBlockPublishingEnabled;
  private final boolean gossipBlobsAfterBlock;

  protected final BlockFactory blockFactory;
  protected final BlockImportChannel blockImportChannel;
  protected final BlockGossipChannel blockGossipChannel;
  protected final DutyMetrics dutyMetrics;

  public AbstractBlockPublisher(
      final AsyncRunner asyncRunner,
      final BlockFactory blockFactory,
      final BlockGossipChannel blockGossipChannel,
      final BlockImportChannel blockImportChannel,
      final DutyMetrics dutyMetrics,
      final boolean gossipBlobsAfterBlock) {
    this(
        asyncRunner,
        null,
        blockFactory,
        blockGossipChannel,
        blockImportChannel,
        dutyMetrics,
        gossipBlobsAfterBlock,
        false);
  }

  public AbstractBlockPublisher(
      final AsyncRunner asyncRunner,
      final Spec spec,
      final BlockFactory blockFactory,
      final BlockGossipChannel blockGossipChannel,
      final BlockImportChannel blockImportChannel,
      final DutyMetrics dutyMetrics,
      final boolean gossipBlobsAfterBlock,
      final boolean isLateBlockPublishingEnabled) {
    this.asyncRunner = asyncRunner;
    this.spec = spec;
    this.blockFactory = blockFactory;
    this.blockImportChannel = blockImportChannel;
    this.blockGossipChannel = blockGossipChannel;
    this.dutyMetrics = dutyMetrics;
    this.gossipBlobsAfterBlock = gossipBlobsAfterBlock;
    this.isLateBlockPublishingEnabled = isLateBlockPublishingEnabled;
  }

  @Override
  public SafeFuture<SendSignedBlockResult> sendSignedBlock(
      final SignedBlockContainer blockContainer,
      final BroadcastValidationLevel broadcastValidationLevel,
      final BlockPublishingPerformance blockPublishingPerformance) {
    return blockFactory
        .unblindSignedBlockIfBlinded(blockContainer.getSignedBlock(), blockPublishingPerformance)
        .thenCompose(
            // creating blob sidecars after unblinding the block to ensure in the blinded flow we
            // will have the cached builder payload
            maybeSignedBlock -> {
              // Fulu, Builder didn't reveal full block
              if (maybeSignedBlock.isEmpty()) {
                return SafeFuture.completedFuture(
                    new BlockImportAndBroadcastValidationResults(
                        SafeFuture.completedFuture(BlockImportResult.BUILDER_WITHHOLD)));
              }

              final SignedBeaconBlock signedBeaconBlock = maybeSignedBlock.get();
              if (blockContainer.supportsCellProofs()) {
                return gossipAndImportUnblindedSignedBlockAndDataColumnSidecars(
                    signedBeaconBlock,
                    Suppliers.memoize(() -> blockFactory.createDataColumnSidecars(blockContainer)),
                    broadcastValidationLevel,
                    blockPublishingPerformance);
              } else {
                return gossipAndImportUnblindedSignedBlockAndBlobSidecars(
                    signedBeaconBlock,
                    Suppliers.memoize(() -> blockFactory.createBlobSidecars(blockContainer)),
                    broadcastValidationLevel,
                    blockPublishingPerformance);
              }
            })
        .thenCompose(result -> calculateResult(blockContainer, result, blockPublishingPerformance));
  }

  private SafeFuture<BlockImportAndBroadcastValidationResults>
      gossipAndImportUnblindedSignedBlockAndBlobSidecars(
          final SignedBeaconBlock block,
          final Supplier<List<BlobSidecar>> blobSidecars,
          final BroadcastValidationLevel broadcastValidationLevel,
          final BlockPublishingPerformance blockPublishingPerformance) {

    if (broadcastValidationLevel == BroadcastValidationLevel.NOT_REQUIRED) {
      // when broadcast validation is disabled, we can import immediately and publish the block
      // (and blob sidecars) without waiting for validation
      delayBlockPublishing(block)
          .thenRun(() -> publishBlockAndBlobs(block, blobSidecars, blockPublishingPerformance))
          .finishError(LOG);
      importBlobSidecars(blobSidecars.get(), blockPublishingPerformance);
      return importBlock(block, broadcastValidationLevel, blockPublishingPerformance);
    }

    // when broadcast validation is enabled, we need to wait for the validation to complete before
    // publishing the block (and blob sidecars)

    final SafeFuture<BlockImportAndBroadcastValidationResults>
        blockImportAndBroadcastValidationResults =
            importBlock(block, broadcastValidationLevel, blockPublishingPerformance);

    // prepare and import blob sidecars in parallel with block import
    asyncRunner
        .runAsync(() -> importBlobSidecars(blobSidecars.get(), blockPublishingPerformance))
        .finish(
            error ->
                LOG.error("Failed to import blob sidecars for slot {}", block.getSlot(), error));

    blockImportAndBroadcastValidationResults
        .thenCompose(BlockImportAndBroadcastValidationResults::broadcastValidationResult)
        .thenAccept(
            broadcastValidationResult -> {
              if (broadcastValidationResult == BroadcastValidationResult.SUCCESS) {
                delayBlockPublishing(block)
                    .thenRun(
                        () -> publishBlockAndBlobs(block, blobSidecars, blockPublishingPerformance))
                    .finishError(LOG);
                LOG.debug("Block (and blob sidecars) publishing scheduled");
              } else {
                LOG.warn(
                    "Block (and blob sidecars) publishing skipped due to broadcast validation result {} for slot {}",
                    broadcastValidationResult,
                    block.getSlot());
              }
            })
        .finish(
            err ->
                LOG.error(
                    "Block (and blob sidecars) publishing failed for slot {}",
                    block.getSlot(),
                    err));

    return blockImportAndBroadcastValidationResults;
  }

  private SafeFuture<BlockImportAndBroadcastValidationResults>
      gossipAndImportUnblindedSignedBlockAndDataColumnSidecars(
          final SignedBeaconBlock block,
          final Supplier<List<DataColumnSidecar>> dataColumnSidecars,
          final BroadcastValidationLevel broadcastValidationLevel,
          final BlockPublishingPerformance blockPublishingPerformance) {

    if (broadcastValidationLevel == BroadcastValidationLevel.NOT_REQUIRED) {
      // when broadcast validation is disabled, we can import immediately and publish the block
      // (and data column sidecars) without waiting for validation
      delayBlockPublishing(block)
          .thenRun(
              () ->
                  publishBlockAndDataColumnSidecars(
                      block, dataColumnSidecars, blockPublishingPerformance))
          .finishError(LOG);
      return importBlock(block, broadcastValidationLevel, blockPublishingPerformance);
    }

    // when broadcast validation is enabled, we need to wait for the validation to complete before
    // publishing the block (and data column sidecars)

    final SafeFuture<BlockImportAndBroadcastValidationResults>
        blockImportAndBroadcastValidationResults =
            importBlock(block, broadcastValidationLevel, blockPublishingPerformance);

    blockImportAndBroadcastValidationResults
        .thenCompose(BlockImportAndBroadcastValidationResults::broadcastValidationResult)
        .thenAccept(
            broadcastValidationResult -> {
              if (broadcastValidationResult == BroadcastValidationResult.SUCCESS) {
                delayBlockPublishing(block)
                    .thenRun(
                        () ->
                            publishBlockAndDataColumnSidecars(
                                block, dataColumnSidecars, blockPublishingPerformance))
                    .finishError(LOG);
                LOG.debug("Block (and data column sidecars) publishing scheduled");
              } else {
                LOG.warn(
                    "Block (and data column sidecars) publishing skipped due to broadcast validation result {} for slot {}",
                    broadcastValidationResult,
                    block.getSlot());
              }
            })
        .finish(
            err ->
                LOG.error(
                    "Block (and data column sidecars) publishing failed for slot {}",
                    block.getSlot(),
                    err));

    return blockImportAndBroadcastValidationResults;
  }

  private SafeFuture<Void> delayBlockPublishing(final SignedBeaconBlock block) {
    if (!isLateBlockPublishingEnabled) {
      return SafeFuture.COMPLETE;
    }

    final UInt64 slot = block.getSlot();
    final int delayMillis =
        DELAYS_MILLIS
            .get(LAST_DELAY_INDEX.getAndUpdate(i -> (i + 1) % DELAYS_MILLIS.size()))
            .apply(spec, slot);
    LOG.info("delaying block publishing for slot {} by {} millis", slot, delayMillis);
    return new SafeFuture<Void>()
        .orTimeout(delayMillis, TimeUnit.MILLISECONDS)
        .exceptionally(ignore -> null)
        .toVoid();
  }

  private void publishBlockAndBlobs(
      final SignedBeaconBlock block,
      final Supplier<List<BlobSidecar>> blobSidecars,
      final BlockPublishingPerformance blockPublishingPerformance) {

    if (gossipBlobsAfterBlock) {
      publishBlock(block, blockPublishingPerformance)
          .always(() -> publishBlobSidecars(blobSidecars.get(), blockPublishingPerformance));
    } else {
      publishBlock(block, blockPublishingPerformance).finishStackTrace();
      publishBlobSidecars(blobSidecars.get(), blockPublishingPerformance);
    }
  }

  private void publishBlockAndDataColumnSidecars(
      final SignedBeaconBlock block,
      final Supplier<List<DataColumnSidecar>> dataColumnSidecars,
      final BlockPublishingPerformance blockPublishingPerformance) {

    if (gossipBlobsAfterBlock) {
      publishBlock(block, blockPublishingPerformance)
          .always(
              () ->
                  publishDataColumnSidecars(dataColumnSidecars.get(), blockPublishingPerformance));
    } else {
      publishBlock(block, blockPublishingPerformance).finishStackTrace();
      publishDataColumnSidecars(dataColumnSidecars.get(), blockPublishingPerformance);
    }
  }

  abstract SafeFuture<BlockImportAndBroadcastValidationResults> importBlock(
      SignedBeaconBlock block,
      BroadcastValidationLevel broadcastValidationLevel,
      BlockPublishingPerformance blockPublishingPerformance);

  abstract void importBlobSidecars(
      List<BlobSidecar> blobSidecars, BlockPublishingPerformance blockPublishingPerformance);

  abstract SafeFuture<Void> publishBlock(
      SignedBeaconBlock block, BlockPublishingPerformance blockPublishingPerformance);

  abstract void publishBlobSidecars(
      List<BlobSidecar> blobSidecars, BlockPublishingPerformance blockPublishingPerformance);

  abstract void publishDataColumnSidecars(
      List<DataColumnSidecar> dataColumnSidecars,
      BlockPublishingPerformance blockPublishingPerformance);

  private SafeFuture<SendSignedBlockResult> calculateResult(
      final SignedBlockContainer maybeBlindedBlockContainer,
      final BlockImportAndBroadcastValidationResults blockImportAndBroadcastValidationResults,
      final BlockPublishingPerformance blockPublishingPerformance) {

    // broadcast validation can fail earlier than block import.
    // The assumption is that in that block import will fail but not as fast
    // (there might be the state transition in progress)
    // Thus, to let the API return as soon as possible, let's check broadcast validation first.
    return blockImportAndBroadcastValidationResults
        .broadcastValidationResult()
        .thenCompose(
            broadcastValidationResult -> {
              if (broadcastValidationResult.isFailure()) {
                return SafeFuture.completedFuture(
                    SendSignedBlockResult.rejected(
                        FAILED_BROADCAST_VALIDATION.name()
                            + ": "
                            + broadcastValidationResult.name()));
              }

              return blockImportAndBroadcastValidationResults
                  .blockImportResult()
                  .thenApply(
                      importResult -> {
                        blockPublishingPerformance.blockImportCompleted();
                        if (importResult.isSuccessful()) {
                          LOG.trace(
                              "Successfully imported proposed block: {}",
                              maybeBlindedBlockContainer.getSignedBlock().toLogString());
                          dutyMetrics.onBlockPublished(maybeBlindedBlockContainer.getSlot());
                          return SendSignedBlockResult.success(
                              maybeBlindedBlockContainer.getRoot());
                        }
                        if (importResult.getFailureReason() == FailureReason.BLOCK_IS_FROM_FUTURE) {
                          LOG.debug(
                              "Delayed processing proposed block {} because it is from the future",
                              maybeBlindedBlockContainer.getSignedBlock().toLogString());
                          dutyMetrics.onBlockPublished(maybeBlindedBlockContainer.getSlot());
                          return SendSignedBlockResult.notImported(
                              importResult.getFailureReason().name());
                        }
                        if (importResult.getFailureReason() == FailureReason.BUILDER_WITHHOLD) {
                          LOG.debug(
                              "Block was not imported because builder didn't reveal full block {}",
                              maybeBlindedBlockContainer.getSignedBlock().toLogString());
                          dutyMetrics.onBlockPublished(maybeBlindedBlockContainer.getSlot());
                          return SendSignedBlockResult.notImported(
                              importResult.getFailureReason().name());
                        }

                        VALIDATOR_LOGGER.proposedBlockImportFailed(
                            importResult.getFailureReason().toString(),
                            maybeBlindedBlockContainer.getSlot(),
                            maybeBlindedBlockContainer.getRoot(),
                            importResult.getFailureCause());

                        return SendSignedBlockResult.notImported(
                            importResult.getFailureReason().name());
                      });
            });
  }
}
