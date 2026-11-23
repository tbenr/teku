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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.spec.config.SpecConfigGloas.BUILDER_INDEX_SELF_BUILD;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.async.StubAsyncRunner;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networking.eth2.gossip.DataColumnSidecarGossipChannel;
import tech.pegasys.teku.networking.eth2.gossip.ExecutionPayloadGossipChannel;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blobs.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadBid;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelopeContents;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionRequests;
import tech.pegasys.teku.spec.datastructures.validator.BroadcastValidationLevel;
import tech.pegasys.teku.spec.logic.common.statetransition.results.ExecutionPayloadImportResult;
import tech.pegasys.teku.spec.logic.common.statetransition.results.ExecutionPayloadImportResult.FailureReason;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.statetransition.blobs.RemoteOrigin;
import tech.pegasys.teku.statetransition.execution.ExecutionPayloadManager;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;
import tech.pegasys.teku.storage.client.RecentChainData;
import tech.pegasys.teku.storage.store.UpdatableStore;
import tech.pegasys.teku.validator.api.PublishSignedExecutionPayloadResult;
import tech.pegasys.teku.validator.coordinator.ExecutionPayloadFactory;

class ExecutionPayloadPublisherGloasTest {

  private final Spec spec = TestSpecFactory.createMinimalGloas();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final UInt64 slot = UInt64.valueOf(42);
  private final SchemaDefinitionsGloas schemaDefinitions =
      SchemaDefinitionsGloas.required(spec.atSlot(slot).getSchemaDefinitions());

  private final ExecutionPayloadFactory executionPayloadFactory =
      mock(ExecutionPayloadFactory.class);
  private final ExecutionPayloadGossipChannel executionPayloadGossipChannel =
      mock(ExecutionPayloadGossipChannel.class);
  private final DataColumnSidecarGossipChannel dataColumnSidecarGossipChannel =
      mock(DataColumnSidecarGossipChannel.class);
  private final ExecutionPayloadManager executionPayloadManager =
      mock(ExecutionPayloadManager.class);
  private final RecentChainData recentChainData = mock(RecentChainData.class);
  private final UpdatableStore store = mock(UpdatableStore.class);
  private final StubAsyncRunner asyncRunner = new StubAsyncRunner();

  private final ExecutionPayloadPublisherGloas executionPayloadPublisher =
      new ExecutionPayloadPublisherGloas(
          spec,
          asyncRunner,
          executionPayloadFactory,
          executionPayloadGossipChannel,
          dataColumnSidecarGossipChannel,
          executionPayloadManager,
          recentChainData);

  private final ExecutionPayload payload = dataStructureUtil.randomExecutionPayload(slot);
  private final ExecutionRequests executionRequests =
      dataStructureUtil.randomExecutionRequests(slot);
  private final List<DataColumnSidecar> dataColumnSidecars =
      List.of(dataStructureUtil.randomDataColumnSidecar());

  @Test
  public void shouldValidateAndPublishWhenEnvelopeMatchesTheBlock() {
    final SignedBeaconBlock block = blockCommittingTo(payload, executionRequests, 0);
    final ExecutionPayloadEnvelopeContents contents = contentsFor(block);
    prepareKnownBlock(block);
    prepareValidation(contents, InternalValidationResult.ACCEPT);

    assertThat(publish(contents))
        .isCompletedWithValue(PublishSignedExecutionPayloadResult.success(block.getRoot()));

    verify(executionPayloadGossipChannel)
        .publishExecutionPayload(contents.getExecutionPayloadEnvelope());
    verify(dataColumnSidecarGossipChannel)
        .publishDataColumnSidecars(List.of(), RemoteOrigin.LOCAL_PROPOSAL);
  }

  @Test
  public void shouldRejectWithoutPublishingWhenBroadcastValidationFails() {
    final SignedBeaconBlock block = blockCommittingTo(payload, executionRequests, 0);
    final ExecutionPayloadEnvelopeContents contents = contentsFor(block);
    prepareKnownBlock(block);
    prepareValidation(contents, InternalValidationResult.reject("invalid"));

    assertThat(publish(contents))
        .isCompletedWithValue(
            PublishSignedExecutionPayloadResult.rejected(
                block.getRoot(), "Failed broadcast validation: invalid"));

    verifyNoInteractions(executionPayloadGossipChannel, dataColumnSidecarGossipChannel);
  }

  @Test
  public void shouldRejectWithoutPublishingWhenPayloadDoesNotMatchTheBid() {
    final SignedBeaconBlock block =
        blockCommittingTo(dataStructureUtil.randomExecutionPayload(slot), executionRequests, 0);
    prepareKnownBlock(block);

    assertRejectedWithoutPublishing(contentsFor(block));
  }

  @Test
  public void shouldRejectWithoutPublishingWhenExecutionRequestsDoNotMatchTheBid() {
    final SignedBeaconBlock block =
        blockCommittingTo(payload, dataStructureUtil.randomExecutionRequests(slot), 0);
    prepareKnownBlock(block);

    assertRejectedWithoutPublishing(contentsFor(block));
  }

  @Test
  public void shouldRejectWithoutPublishingWhenBlobDataIsMissingForTheBidCommitments() {
    final SignedBeaconBlock block = blockCommittingTo(payload, executionRequests, 2);
    prepareKnownBlock(block);

    // no blobs and no KZG proofs for a bid committing to two blobs
    assertRejectedWithoutPublishing(contentsFor(block));
  }

  @Test
  public void shouldRejectWithoutPublishingWhenBlobDataDoesNotMatchTheBidCommitments() {
    final SignedBeaconBlock block = blockCommittingTo(payload, executionRequests, 0);
    final ExecutionPayloadEnvelopeContents contents = contentsFor(block);
    prepareKnownBlock(block);
    // the bid commits to no blobs, yet the request yields data column sidecars
    when(executionPayloadFactory.createDataColumnSidecars(contents)).thenReturn(dataColumnSidecars);

    assertRejectedWithoutPublishing(contents);
  }

  @Test
  public void shouldOnlyPublishBlobDataWhenEnvelopeIsAlreadyKnown() {
    final SignedBeaconBlock block = blockCommittingTo(payload, executionRequests, 0);
    final ExecutionPayloadEnvelopeContents contents = contentsFor(block);
    prepareKnownBlock(block);
    when(executionPayloadManager.isExecutionPayloadRecentlySeen(block.getRoot())).thenReturn(true);

    assertThat(publish(contents))
        .isCompletedWithValue(PublishSignedExecutionPayloadResult.success(block.getRoot()));

    verify(dataColumnSidecarGossipChannel)
        .publishDataColumnSidecars(List.of(), RemoteOrigin.LOCAL_PROPOSAL);
    verifyNoInteractions(executionPayloadGossipChannel);
    verify(executionPayloadManager, never())
        .validateAndImportExecutionPayloadForBroadcast(any(), any());
  }

  @Test
  public void shouldWaitForTheImportOfAKnownEnvelopeWhichIsStillInProgress() {
    final SignedBeaconBlock block = blockCommittingTo(payload, executionRequests, 0);
    prepareKnownBlock(block);
    when(executionPayloadManager.isExecutionPayloadRecentlySeen(block.getRoot())).thenReturn(true);
    final SafeFuture<Optional<FailureReason>> importFailure = new SafeFuture<>();
    when(executionPayloadManager.getRecentExecutionPayloadImportFailure(block.getRoot()))
        .thenReturn(Optional.of(importFailure));

    final SafeFuture<PublishSignedExecutionPayloadResult> result = publish(contentsFor(block));

    // the blob data is published without waiting
    verify(dataColumnSidecarGossipChannel)
        .publishDataColumnSidecars(List.of(), RemoteOrigin.LOCAL_PROPOSAL);
    assertThat(result).isNotDone();

    importFailure.complete(Optional.empty());

    assertThat(result)
        .isCompletedWithValue(PublishSignedExecutionPayloadResult.success(block.getRoot()));
    verifyNoInteractions(executionPayloadGossipChannel);
  }

  @Test
  public void shouldReportAKnownEnvelopeWhichFailedToImportAsNotImported() {
    final SignedBeaconBlock block = blockCommittingTo(payload, executionRequests, 0);
    prepareKnownBlock(block);
    when(executionPayloadManager.isExecutionPayloadRecentlySeen(block.getRoot())).thenReturn(true);
    when(executionPayloadManager.getRecentExecutionPayloadImportFailure(block.getRoot()))
        .thenReturn(
            Optional.of(SafeFuture.completedFuture(Optional.of(FailureReason.FAILED_EXECUTION))));

    assertThat(publish(contentsFor(block)))
        .isCompletedWithValue(
            PublishSignedExecutionPayloadResult.notImported(block.getRoot(), "FAILED_EXECUTION"));

    verify(dataColumnSidecarGossipChannel)
        .publishDataColumnSidecars(List.of(), RemoteOrigin.LOCAL_PROPOSAL);
    verifyNoInteractions(executionPayloadGossipChannel);
    verify(executionPayloadManager, never())
        .validateAndImportExecutionPayloadForBroadcast(any(), any());
  }

  @Test
  public void shouldReturnBlockNotFoundAtOnceWhenEnvelopeIsNotForTheCurrentSlot() {
    final SignedBeaconBlock block = blockCommittingTo(payload, executionRequests, 0);
    prepareUnknownBlock(block, slot.plus(1));

    final SafeFuture<PublishSignedExecutionPayloadResult> result =
        executionPayloadPublisher.publishSignedExecutionPayload(
            contentsFor(block), Optional.empty());

    assertThat(result)
        .isCompletedWithValue(PublishSignedExecutionPayloadResult.blockNotFound(block.getRoot()));
    assertThat(asyncRunner.hasDelayedActions()).isFalse();
    verifyNoInteractions(executionPayloadGossipChannel, dataColumnSidecarGossipChannel);
  }

  @Test
  public void shouldReturnBlockNotFoundWhenBlockDoesNotArriveByTheEndOfTheSlot() {
    final SignedBeaconBlock block = blockCommittingTo(payload, executionRequests, 0);
    prepareUnknownBlock(block, slot);

    final SafeFuture<PublishSignedExecutionPayloadResult> result =
        executionPayloadPublisher.publishSignedExecutionPayload(
            contentsFor(block), Optional.empty());
    assertThat(result).isNotDone();

    // end of the slot
    asyncRunner.executeQueuedActions();

    assertThat(result)
        .isCompletedWithValue(PublishSignedExecutionPayloadResult.blockNotFound(block.getRoot()));
    verifyNoInteractions(executionPayloadGossipChannel, dataColumnSidecarGossipChannel);
  }

  @Test
  public void shouldPublishOnceTheAwaitedBlockIsImported() {
    final SignedBeaconBlock block = blockCommittingTo(payload, executionRequests, 0);
    final ExecutionPayloadEnvelopeContents contents = contentsFor(block);
    prepareUnknownBlock(block, slot);
    prepareValidation(contents, InternalValidationResult.ACCEPT);

    final SafeFuture<PublishSignedExecutionPayloadResult> result =
        executionPayloadPublisher.publishSignedExecutionPayload(contents, Optional.empty());
    assertThat(result).isNotDone();

    prepareKnownBlock(block);
    executionPayloadPublisher.onBlockImported(block, false);
    asyncRunner.executeQueuedActions();

    assertThat(result)
        .isCompletedWithValue(PublishSignedExecutionPayloadResult.success(block.getRoot()));
    verify(executionPayloadGossipChannel)
        .publishExecutionPayload(contents.getExecutionPayloadEnvelope());
  }

  @Test
  public void onBlockImported_shouldPublishSelfBuiltExecutionPayload() {
    final SignedBeaconBlock block = blockWithBidFrom(BUILDER_INDEX_SELF_BUILD);
    final ExecutionPayloadEnvelopeContents contents = contentsFor(block);
    final ExecutionPayloadEnvelope executionPayload = contents.getExecutionPayloadEnvelope();
    when(executionPayloadFactory.createSelfBuiltExecutionPayload(block.getMessage()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(contents)));
    when(executionPayloadFactory.createDataColumnSidecars(contents)).thenReturn(dataColumnSidecars);
    prepareValidation(contents, InternalValidationResult.ACCEPT);

    executionPayloadPublisher.onBlockImported(block, false);
    asyncRunner.executeQueuedActions();

    verify(executionPayloadManager)
        .validateAndImportExecutionPayloadForBroadcast(
            executionPayload, Optional.of(BroadcastValidationLevel.GOSSIP));
    verify(executionPayloadGossipChannel).publishExecutionPayload(executionPayload);
    verify(dataColumnSidecarGossipChannel)
        .publishDataColumnSidecars(dataColumnSidecars, RemoteOrigin.LOCAL_PROPOSAL);
  }

  @Test
  public void onBlockImported_shouldNotPublishForBidFromBuilder() {
    executionPayloadPublisher.onBlockImported(blockWithBidFrom(UInt64.valueOf(7)), false);
    asyncRunner.executeQueuedActions();

    verifyNoInteractions(
        executionPayloadFactory, executionPayloadGossipChannel, dataColumnSidecarGossipChannel);
  }

  @Test
  public void onBlockImported_shouldNotPublishWhenExecutionPayloadIsAlreadyKnown() {
    final SignedBeaconBlock block = blockWithBidFrom(BUILDER_INDEX_SELF_BUILD);
    when(executionPayloadManager.isExecutionPayloadRecentlySeen(block.getRoot())).thenReturn(true);

    executionPayloadPublisher.onBlockImported(block, false);
    asyncRunner.executeQueuedActions();

    verifyNoInteractions(
        executionPayloadFactory, executionPayloadGossipChannel, dataColumnSidecarGossipChannel);
  }

  @Test
  public void onBlockImported_shouldNotPublishWhenNoLocalPayloadMatchesTheBid() {
    final SignedBeaconBlock block = blockWithBidFrom(BUILDER_INDEX_SELF_BUILD);
    when(executionPayloadFactory.createSelfBuiltExecutionPayload(block.getMessage()))
        .thenReturn(SafeFuture.completedFuture(Optional.empty()));

    executionPayloadPublisher.onBlockImported(block, false);
    asyncRunner.executeQueuedActions();

    verifyNoInteractions(executionPayloadGossipChannel, dataColumnSidecarGossipChannel);
  }

  @Test
  public void onBlockImported_shouldNotThrowWhenSelfBuiltExecutionPayloadCannotBeCreated() {
    final SignedBeaconBlock block = blockWithBidFrom(BUILDER_INDEX_SELF_BUILD);
    when(executionPayloadFactory.createSelfBuiltExecutionPayload(block.getMessage()))
        .thenReturn(SafeFuture.failedFuture(new RuntimeException("no payload")));

    executionPayloadPublisher.onBlockImported(block, false);
    asyncRunner.executeQueuedActions();

    verifyNoInteractions(executionPayloadGossipChannel, dataColumnSidecarGossipChannel);
  }

  /** A block whose bid commits to the given payload, execution requests and number of blobs. */
  private SignedBeaconBlock blockCommittingTo(
      final ExecutionPayload payload, final ExecutionRequests requests, final int blobCount) {
    return blockWith(
        schemaDefinitions
            .getExecutionPayloadBidSchema()
            .create(
                payload.getParentHash(),
                dataStructureUtil.randomBytes32(),
                payload.getBlockHash(),
                payload.hashTreeRoot(),
                payload.getPrevRandao(),
                payload.getFeeRecipient(),
                payload.getGasLimit(),
                UInt64.valueOf(7),
                slot,
                UInt64.ONE,
                UInt64.ZERO,
                schemaDefinitions
                    .getBlobKzgCommitmentsSchema()
                    .createFromElements(
                        IntStream.range(0, blobCount)
                            .mapToObj(__ -> dataStructureUtil.randomSszKZGCommitment())
                            .toList()),
                requests.hashTreeRoot()));
  }

  private SignedBeaconBlock blockWithBidFrom(final UInt64 builderIndex) {
    return blockWith(dataStructureUtil.randomExecutionPayloadBid(slot, builderIndex));
  }

  private SignedBeaconBlock blockWith(final ExecutionPayloadBid bid) {
    return dataStructureUtil.signedBlock(
        dataStructureUtil.randomBeaconBlock(
            slot,
            dataStructureUtil.randomBeaconBlockBody(
                slot,
                builder ->
                    builder.signedExecutionPayloadBid(
                        dataStructureUtil.randomSignedExecutionPayloadBid(bid)))));
  }

  /** The contents revealing {@code payload} for the block, with no blob data. */
  private ExecutionPayloadEnvelopeContents contentsFor(final SignedBeaconBlock block) {
    return dataStructureUtil.executionPayloadEnvelopeContents(
        schemaDefinitions
            .getExecutionPayloadEnvelopeSchema()
            .create(payload, executionRequests, block.getRoot(), block.getParentRoot()));
  }

  private void prepareKnownBlock(final SignedBeaconBlock block) {
    when(recentChainData.retrieveBlockByRoot(block.getRoot()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(block.getMessage())));
    when(recentChainData.containsBlock(block.getRoot())).thenReturn(true);
  }

  /** The block is unknown and the node is at the start of {@code currentSlot}. */
  private void prepareUnknownBlock(final SignedBeaconBlock block, final UInt64 currentSlot) {
    when(recentChainData.retrieveBlockByRoot(block.getRoot()))
        .thenReturn(SafeFuture.completedFuture(Optional.empty()));
    when(recentChainData.containsBlock(block.getRoot())).thenReturn(false);
    when(recentChainData.getCurrentSlot()).thenReturn(Optional.of(currentSlot));
    when(recentChainData.getGenesisTimeMillis()).thenReturn(UInt64.ZERO);
    when(recentChainData.getStore()).thenReturn(store);
    when(store.getTimeInMillis())
        .thenReturn(spec.computeTimeMillisAtSlot(currentSlot, UInt64.ZERO));
  }

  private void prepareValidation(
      final ExecutionPayloadEnvelopeContents contents,
      final InternalValidationResult validationResult) {
    final ExecutionPayloadEnvelope envelope = contents.getExecutionPayloadEnvelope();
    when(executionPayloadManager.validateAndImportExecutionPayloadForBroadcast(eq(envelope), any()))
        .thenReturn(
            SafeFuture.completedFuture(
                new ExecutionPayloadManager.ValidateAndImportResult(
                    validationResult,
                    validationResult.isAccept()
                        ? Optional.of(
                            SafeFuture.completedFuture(
                                ExecutionPayloadImportResult.successful(envelope)))
                        : Optional.empty())));
    when(executionPayloadGossipChannel.publishExecutionPayload(envelope))
        .thenReturn(SafeFuture.COMPLETE);
  }

  /** Submits the contents and runs the blob data verification scheduled for them. */
  private SafeFuture<PublishSignedExecutionPayloadResult> publish(
      final ExecutionPayloadEnvelopeContents contents) {
    final SafeFuture<PublishSignedExecutionPayloadResult> result =
        executionPayloadPublisher.publishSignedExecutionPayload(contents, Optional.empty());
    asyncRunner.executeQueuedActions();
    return result;
  }

  private void assertRejectedWithoutPublishing(final ExecutionPayloadEnvelopeContents contents) {
    final SafeFuture<PublishSignedExecutionPayloadResult> result = publish(contents);

    assertThat(result).isCompleted();
    assertThat(result.getImmediately().outcome())
        .isEqualTo(PublishSignedExecutionPayloadResult.Outcome.REJECTED);
    verifyNoInteractions(executionPayloadGossipChannel, dataColumnSidecarGossipChannel);
    verify(executionPayloadManager, never())
        .validateAndImportExecutionPayloadForBroadcast(any(), any());
  }
}
