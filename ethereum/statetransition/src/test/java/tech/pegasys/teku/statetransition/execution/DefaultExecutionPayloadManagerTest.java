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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.infrastructure.async.SafeFuture.completedFuture;
import static tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus.PAYLOAD_STATUS_EMPTY;
import static tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus.PAYLOAD_STATUS_FULL;
import static tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus.PAYLOAD_STATUS_PENDING;
import static tech.pegasys.teku.statetransition.validation.InternalValidationResult.ACCEPT;
import static tech.pegasys.teku.statetransition.validation.InternalValidationResult.SAVE_FOR_FUTURE;
import static tech.pegasys.teku.statetransition.validation.InternalValidationResult.reject;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.pegasys.infrastructure.logging.LogCaptor;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.async.SafeFutureAssert;
import tech.pegasys.teku.infrastructure.async.StubAsyncRunner;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.BlindedExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionRequests;
import tech.pegasys.teku.spec.executionlayer.ExecutionLayerChannel;
import tech.pegasys.teku.spec.logic.common.statetransition.results.ExecutionPayloadImportResult;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.statetransition.execution.ExecutionPayloadManager.ValidateAndImportResult;
import tech.pegasys.teku.statetransition.forkchoice.ForkChoice;
import tech.pegasys.teku.statetransition.validation.ExecutionPayloadGossipValidator;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;
import tech.pegasys.teku.storage.client.RecentChainData;
import tech.pegasys.teku.storage.store.UpdatableStore;

class DefaultExecutionPayloadManagerTest {

  private final Spec spec = TestSpecFactory.createMinimalGloas();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);

  private final StubAsyncRunner asyncRunner = new StubAsyncRunner();

  private final ExecutionPayloadGossipValidator executionPayloadGossipValidator =
      mock(ExecutionPayloadGossipValidator.class);
  private final ForkChoice forkChoice = mock(ForkChoice.class);
  private final ExecutionLayerChannel executionLayer = mock(ExecutionLayerChannel.class);
  private final ReceivedExecutionPayloadEventsChannel
      receivedExecutionPayloadEventsChannelPublisher =
          mock(ReceivedExecutionPayloadEventsChannel.class);
  private final RecentChainData recentChainData = mock(RecentChainData.class);
  private final Set<Bytes32> blockRootsWithInvalidExecutionPayload = new HashSet<>();
  private final UpdatableStore store = mock(UpdatableStore.class);

  private Optional<ExecutionPayloadEnvelope> publishedExecutionPayload = Optional.empty();

  private final DefaultExecutionPayloadManager executionPayloadManager =
      new DefaultExecutionPayloadManager(
          spec,
          asyncRunner,
          executionPayloadGossipValidator,
          forkChoice,
          executionLayer,
          receivedExecutionPayloadEventsChannelPublisher,
          recentChainData,
          blockRootsWithInvalidExecutionPayload,
          executionPayload -> {
            publishedExecutionPayload = Optional.of(executionPayload);
            return SafeFuture.COMPLETE;
          });

  private final ExecutionPayloadEnvelope signedExecutionPayload =
      dataStructureUtil.randomExecutionPayloadEnvelope(42);

  private final ExecutionPayload committedPayload =
      dataStructureUtil.randomExecutionPayload(UInt64.valueOf(42));
  private final ExecutionRequests committedExecutionRequests =
      dataStructureUtil.randomExecutionRequests(UInt64.valueOf(42));
  // commits to committedExecutionPayload
  private final SignedBeaconBlock block =
      dataStructureUtil.randomSignedBeaconBlockCommittingTo(
          committedPayload, committedExecutionRequests);
  private final ExecutionPayloadEnvelope committedExecutionPayload =
      dataStructureUtil.executionPayloadEnvelopeForBlock(
          block, committedPayload, committedExecutionRequests);

  @BeforeEach
  void setUp() {
    executionPayloadManager.onSlot(UInt64.valueOf(42));
    when(recentChainData.getGenesisTimeMillis()).thenReturn(UInt64.ZERO);
    when(recentChainData.getStore()).thenReturn(store);
    when(store.getTimeInMillis()).thenReturn(UInt64.ZERO);
  }

  @Test
  public void shouldDelegateIsExecutionPayloadRecentlySeenToTheGossipValidator() {
    final Bytes32 beaconBlockRoot = dataStructureUtil.randomBytes32();
    when(executionPayloadGossipValidator.isPayloadSeen(beaconBlockRoot)).thenReturn(true);
    assertThat(executionPayloadManager.isExecutionPayloadRecentlySeen(beaconBlockRoot)).isTrue();
  }

  @Test
  public void getParentExecutionRequests_shouldReturnImportedRequestsForFullParent() {
    final UInt64 slot = UInt64.valueOf(42);
    final Bytes32 parentRoot = dataStructureUtil.randomBytes32();
    final BlindedExecutionPayloadEnvelope parentExecutionPayload =
        dataStructureUtil.randomBlindedExecutionPayloadEnvelope(slot.longValue());
    when(recentChainData.retrieveSignedBlindedExecutionPayloadByBlockRoot(parentRoot))
        .thenReturn(completedFuture(Optional.of(parentExecutionPayload)));

    final SafeFuture<ExecutionRequests> result =
        executionPayloadManager.getParentExecutionRequestsForBlock(
            slot, parentRoot, PAYLOAD_STATUS_FULL);

    assertThat(result).isCompletedWithValue(parentExecutionPayload.getExecutionRequests());
    verify(recentChainData).retrieveSignedBlindedExecutionPayloadByBlockRoot(parentRoot);
  }

  @Test
  public void getParentExecutionRequestsForBlock_shouldReturnDefaultRequestsForNonFullParent() {
    final UInt64 slot = UInt64.valueOf(42);
    final Bytes32 parentRoot = dataStructureUtil.randomBytes32();
    final ExecutionRequests defaultExecutionRequests = defaultExecutionRequests(slot);

    assertThat(
            executionPayloadManager.getParentExecutionRequestsForBlock(
                slot, parentRoot, PAYLOAD_STATUS_EMPTY))
        .isCompletedWithValue(defaultExecutionRequests);
    assertThat(
            executionPayloadManager.getParentExecutionRequestsForBlock(
                slot, parentRoot, PAYLOAD_STATUS_PENDING))
        .isCompletedWithValue(defaultExecutionRequests);
    verify(recentChainData, never()).retrieveSignedBlindedExecutionPayloadByBlockRoot(parentRoot);
  }

  @Test
  public void getParentExecutionRequestsForBlock_shouldThrowForFullParentWhenPayloadIsMissing() {
    final UInt64 slot = UInt64.valueOf(42);
    final Bytes32 parentRoot = dataStructureUtil.randomBytes32();
    when(recentChainData.retrieveSignedBlindedExecutionPayloadByBlockRoot(parentRoot))
        .thenReturn(completedFuture(Optional.empty()));

    final SafeFuture<ExecutionRequests> result =
        executionPayloadManager.getParentExecutionRequestsForBlock(
            slot, parentRoot, PAYLOAD_STATUS_FULL);

    SafeFutureAssert.assertThatSafeFuture(result)
        .isCompletedExceptionallyWith(IllegalStateException.class)
        .hasMessageContaining(parentRoot.toString())
        .hasMessageContaining(slot.toString());
    verify(recentChainData).retrieveSignedBlindedExecutionPayloadByBlockRoot(parentRoot);
  }

  @Test
  public void shouldValidateAndImport() {
    givenValidationResult(signedExecutionPayload, ACCEPT);
    givenSuccessfulImport(signedExecutionPayload);

    final SafeFuture<InternalValidationResult> resultFuture =
        validateAndImport(signedExecutionPayload);

    try (final LogCaptor logCaptor = LogCaptor.forClass(DefaultExecutionPayloadManager.class)) {
      asyncRunner.executeDueActions();
      logCaptor.assertDebugLog("Successfully imported execution payload");
    }

    assertThat(resultFuture).isCompletedWithValue(ACCEPT);
    verify(receivedExecutionPayloadEventsChannelPublisher)
        .onExecutionPayloadImported(signedExecutionPayload, false);
    assertExecutionPayloadSeenBeforeDeadline(signedExecutionPayload);
  }

  @Test
  public void shouldNotMarkExecutionPayloadSeenBeforeDeadlineWhenReceivedAfterTheDeadline() {
    givenValidationResult(signedExecutionPayload, ACCEPT);
    givenSuccessfulImport(signedExecutionPayload);

    final SafeFuture<InternalValidationResult> resultFuture =
        validateAndImport(
            signedExecutionPayload, payloadDueDeadlineMillis(signedExecutionPayload).plus(1));

    asyncRunner.executeDueActions();

    assertThat(resultFuture).isCompletedWithValue(ACCEPT);
    assertExecutionPayloadNotSeenBeforeDeadline(signedExecutionPayload);
  }

  @Test
  public void shouldNotImportIfValidationFails() {
    final InternalValidationResult rejectedResult = reject("oopsy");
    givenValidationResult(signedExecutionPayload, rejectedResult);

    final SafeFuture<InternalValidationResult> resultFuture =
        validateAndImport(signedExecutionPayload);

    asyncRunner.executeDueActions();

    verifyNoInteractions(forkChoice);
    assertThat(resultFuture).isCompletedWithValue(rejectedResult);
  }

  @Test
  public void shouldHandleInternalErrorsWhileImporting() {
    givenValidationResult(signedExecutionPayload, ACCEPT);
    when(forkChoice.onExecutionPayloadEnvelope(
            eq(signedExecutionPayload), eq(executionLayer), any()))
        .thenThrow(new IllegalStateException("oopsy"));

    final SafeFuture<InternalValidationResult> resultFuture =
        validateAndImport(signedExecutionPayload);

    try (final LogCaptor logCaptor = LogCaptor.forClass(DefaultExecutionPayloadManager.class)) {
      asyncRunner.executeDueActions();
      logCaptor.assertErrorLog("Internal error while importing execution payload");
    }

    assertThat(resultFuture).isCompletedWithValue(ACCEPT);
  }

  @Test
  public void shouldProcessExecutionPayloadWhichHasBeenReceivedBeforeTheBlock() {
    givenValidationResult(committedExecutionPayload, SAVE_FOR_FUTURE);

    assertThat(validateAndImport(committedExecutionPayload, UInt64.ZERO))
        .isCompletedWithValue(SAVE_FOR_FUTURE);

    asyncRunner.executeUntilDone();
    verifyNoInteractions(receivedExecutionPayloadEventsChannelPublisher);

    givenValidationResult(committedExecutionPayload, ACCEPT);
    givenSuccessfulImport(committedExecutionPayload);
    executionPayloadManager.onBlockImported(block, false);

    asyncRunner.executeUntilDone();

    verify(receivedExecutionPayloadEventsChannelPublisher)
        .onExecutionPayloadImported(committedExecutionPayload, false);
    assertThat(publishedExecutionPayload).hasValue(committedExecutionPayload);
    assertExecutionPayloadSeenBeforeDeadline(committedExecutionPayload);
  }

  @Test
  public void shouldProcessTheCommittedExecutionPayloadWhenOthersWereReceivedBeforeTheBlock() {
    final ExecutionPayloadEnvelope firstForgedExecutionPayload = forgedExecutionPayload();
    final ExecutionPayloadEnvelope lastForgedExecutionPayload = forgedExecutionPayload();
    givenValidationResult(firstForgedExecutionPayload, SAVE_FOR_FUTURE);
    givenValidationResult(committedExecutionPayload, SAVE_FOR_FUTURE);
    givenValidationResult(lastForgedExecutionPayload, SAVE_FOR_FUTURE);

    validateAndImportAndJoin(firstForgedExecutionPayload, UInt64.ZERO);
    validateAndImportAndJoin(committedExecutionPayload, UInt64.ONE);
    validateAndImportAndJoin(lastForgedExecutionPayload, UInt64.valueOf(2));

    givenValidationResult(committedExecutionPayload, ACCEPT);
    givenSuccessfulImport(committedExecutionPayload);
    executionPayloadManager.onBlockImported(block, false);

    asyncRunner.executeUntilDone();

    verify(forkChoice)
        .onExecutionPayloadEnvelope(eq(committedExecutionPayload), eq(executionLayer), any());
    verify(forkChoice, never())
        .onExecutionPayloadEnvelope(eq(firstForgedExecutionPayload), eq(executionLayer), any());
    verify(forkChoice, never())
        .onExecutionPayloadEnvelope(eq(lastForgedExecutionPayload), eq(executionLayer), any());
    // only the committed one is validated again once the block is known
    verify(executionPayloadGossipValidator, times(1))
        .validate(firstForgedExecutionPayload, Optional.empty());
    verify(executionPayloadGossipValidator, times(1))
        .validate(lastForgedExecutionPayload, Optional.empty());
    assertThat(publishedExecutionPayload).hasValue(committedExecutionPayload);
  }

  @Test
  public void shouldRejectExecutionPayloadWhichAGossipValidatedBlockDoesNotCommitTo() {
    executionPayloadManager.onBlockValidated(block);
    asyncRunner.executeUntilDone();

    final ExecutionPayloadEnvelope forgedExecutionPayload = forgedExecutionPayload();
    givenValidationResult(forgedExecutionPayload, SAVE_FOR_FUTURE);

    assertThat(validateAndImport(forgedExecutionPayload, UInt64.ZERO))
        .isCompletedWithValueMatching(InternalValidationResult::isReject);

    executionPayloadManager.onBlockImported(block, false);
    asyncRunner.executeUntilDone();

    verify(executionPayloadGossipValidator, times(1))
        .validate(forgedExecutionPayload, Optional.empty());
    verifyNoInteractions(forkChoice);
  }

  @Test
  public void shouldRejectBroadcastOfExecutionPayloadWhichAGossipValidatedBlockDoesNotCommitTo() {
    executionPayloadManager.onBlockValidated(block);
    asyncRunner.executeUntilDone();

    final ExecutionPayloadEnvelope forgedExecutionPayload = forgedExecutionPayload();
    givenValidationResult(forgedExecutionPayload, SAVE_FOR_FUTURE);

    final SafeFuture<ValidateAndImportResult> result =
        executionPayloadManager.validateAndImportExecutionPayloadForBroadcast(
            forgedExecutionPayload, Optional.empty());

    assertThat(result)
        .isCompletedWithValueMatching(
            validateAndImportResult ->
                validateAndImportResult.validationResult().isReject()
                    && validateAndImportResult.importResult().isEmpty());
  }

  @Test
  public void shouldProcessExecutionPayloadHeldAfterItsBlockWasGossipValidated() {
    executionPayloadManager.onBlockValidated(block);
    asyncRunner.executeUntilDone();

    givenValidationResult(committedExecutionPayload, SAVE_FOR_FUTURE);
    assertThat(validateAndImport(committedExecutionPayload, UInt64.ZERO))
        .isCompletedWithValue(SAVE_FOR_FUTURE);

    givenValidationResult(committedExecutionPayload, ACCEPT);
    givenSuccessfulImport(committedExecutionPayload);
    executionPayloadManager.onBlockImported(block, false);

    asyncRunner.executeUntilDone();

    verify(receivedExecutionPayloadEventsChannelPublisher)
        .onExecutionPayloadImported(committedExecutionPayload, false);
    assertThat(publishedExecutionPayload).hasValue(committedExecutionPayload);
    assertExecutionPayloadSeenBeforeDeadline(committedExecutionPayload);
  }

  @Test
  public void shouldNotProcessPendingExecutionPayloadOnceItsSlotHasPassed() {
    givenValidationResult(committedExecutionPayload, SAVE_FOR_FUTURE);
    validateAndImportAndJoin(committedExecutionPayload, UInt64.ZERO);

    executionPayloadManager.onSlot(block.getSlot().increment());
    executionPayloadManager.onBlockImported(block, false);

    asyncRunner.executeUntilDone();

    verify(executionPayloadGossipValidator, times(1))
        .validate(committedExecutionPayload, Optional.empty());
    verifyNoInteractions(forkChoice);
  }

  @Test
  public void shouldNotHoldExecutionPayloadWhenBlockWasImportedWhileValidating() {
    givenValidationResult(committedExecutionPayload, SAVE_FOR_FUTURE);
    when(recentChainData.containsBlock(block.getRoot())).thenReturn(true);

    assertThat(validateAndImport(committedExecutionPayload, UInt64.ZERO))
        .isCompletedWithValue(SAVE_FOR_FUTURE);

    // validated again shortly after instead
    givenValidationResult(committedExecutionPayload, ACCEPT);
    givenSuccessfulImport(committedExecutionPayload);
    asyncRunner.executeUntilDone();

    verify(executionPayloadGossipValidator, times(2))
        .validate(committedExecutionPayload, Optional.empty());
    assertThat(publishedExecutionPayload).hasValue(committedExecutionPayload);
  }

  @Test
  public void shouldCacheInvalidExecutionPayloadWhenImportFailsVerification() {
    final ExecutionPayloadImportResult failedImportResult =
        ExecutionPayloadImportResult.failedVerification(new RuntimeException("invalid"));
    when(forkChoice.onExecutionPayloadEnvelope(
            eq(signedExecutionPayload), eq(executionLayer), any()))
        .thenReturn(completedFuture(failedImportResult));

    final SafeFuture<ExecutionPayloadImportResult> resultFuture =
        executionPayloadManager.importExecutionPayload(signedExecutionPayload, true);
    asyncRunner.executeDueActions();

    assertThat(resultFuture).isCompletedWithValue(failedImportResult);
    assertThat(blockRootsWithInvalidExecutionPayload)
        .contains(signedExecutionPayload.getBeaconBlockRoot());
  }

  @Test
  public void shouldCacheInvalidExecutionPayloadWhenDataAvailabilityCheckIsInvalid() {
    final ExecutionPayloadImportResult failedImportResult =
        ExecutionPayloadImportResult.failedDataAvailabilityCheckInvalid(
            Optional.of(new RuntimeException("invalid")));
    when(forkChoice.onExecutionPayloadEnvelope(
            eq(signedExecutionPayload), eq(executionLayer), any()))
        .thenReturn(completedFuture(failedImportResult));

    final SafeFuture<ExecutionPayloadImportResult> resultFuture =
        executionPayloadManager.importExecutionPayload(signedExecutionPayload, true);
    asyncRunner.executeDueActions();

    assertThat(resultFuture).isCompletedWithValue(failedImportResult);
    assertThat(blockRootsWithInvalidExecutionPayload)
        .contains(signedExecutionPayload.getBeaconBlockRoot());
  }

  @Test
  public void shouldRecordPayloadReportedAvailableBeforeDeadlineAsSeen() {
    // a payload which did not arrive over gossip, for example one fetched by root, is recorded
    // when fork choice reports it available, which it only does once it verified the envelope
    final UInt64 beforeDeadline = payloadDueDeadlineMillis(signedExecutionPayload).minus(1);
    when(store.getTimeInMillis()).thenReturn(beforeDeadline);

    reportAvailableToForkChoicePublisher(signedExecutionPayload);

    assertExecutionPayloadSeenBeforeDeadline(signedExecutionPayload);
    verify(receivedExecutionPayloadEventsChannelPublisher)
        .onExecutionPayloadAvailable(signedExecutionPayload);
  }

  @Test
  public void shouldNotRecordPayloadReportedAvailableAfterDeadlineAsSeen() {
    final UInt64 afterDeadline = payloadDueDeadlineMillis(signedExecutionPayload).plus(1);
    when(store.getTimeInMillis()).thenReturn(afterDeadline);

    reportAvailableToForkChoicePublisher(signedExecutionPayload);

    assertExecutionPayloadNotSeenBeforeDeadline(signedExecutionPayload);
  }

  @Test
  public void shouldRecordPayloadSeenBeforeDeadlineEvenWhenCheckingItTookPastTheDeadline() {
    // the data availability check can take long enough to put an on time payload past its
    // deadline, so the time it arrived is what counts
    final UInt64 beforeDeadline = payloadDueDeadlineMillis(signedExecutionPayload).minus(1);
    final UInt64 afterDeadline = payloadDueDeadlineMillis(signedExecutionPayload).plus(1000);
    when(store.getTimeInMillis()).thenReturn(beforeDeadline);

    reportAvailableToForkChoicePublisher(signedExecutionPayload, Optional.of(afterDeadline));

    assertExecutionPayloadSeenBeforeDeadline(signedExecutionPayload);
  }

  @Test
  public void shouldNotRecordPayloadAsSeenBeforeForkChoiceReportsItAvailable() {
    // the envelope has not been verified at this point, so a forged one must not be recorded
    final UInt64 beforeDeadline = payloadDueDeadlineMillis(signedExecutionPayload).minus(1);
    when(store.getTimeInMillis()).thenReturn(beforeDeadline);
    when(forkChoice.onExecutionPayloadEnvelope(
            eq(signedExecutionPayload), eq(executionLayer), any()))
        .thenReturn(new SafeFuture<>());

    final SafeFuture<ExecutionPayloadImportResult> resultFuture =
        executionPayloadManager.importExecutionPayload(signedExecutionPayload, false);
    asyncRunner.executeDueActions();

    assertThat(resultFuture).isNotCompleted();
    assertExecutionPayloadNotSeenBeforeDeadline(signedExecutionPayload);
  }

  @Test
  public void shouldNotCacheInvalidExecutionPayloadWhenCommitmentNotVerified() {
    final ExecutionPayloadImportResult failedImportResult =
        ExecutionPayloadImportResult.failedVerification(new RuntimeException("invalid"));
    when(forkChoice.onExecutionPayloadEnvelope(
            eq(signedExecutionPayload), eq(executionLayer), any()))
        .thenReturn(completedFuture(failedImportResult));

    // Imports that did not verify the payload commitment (e.g. sync or RPC-by-root) must not poison
    // the invalid-payload cache, even when the import fails verification.
    final SafeFuture<ExecutionPayloadImportResult> resultFuture =
        executionPayloadManager.importExecutionPayload(signedExecutionPayload, false);
    asyncRunner.executeDueActions();

    assertThat(resultFuture).isCompletedWithValue(failedImportResult);
    assertThat(blockRootsWithInvalidExecutionPayload)
        .doesNotContain(signedExecutionPayload.getBeaconBlockRoot());
  }

  private void givenValidationResult(
      final ExecutionPayloadEnvelope executionPayload,
      final InternalValidationResult validationResult) {
    when(executionPayloadGossipValidator.validate(executionPayload, Optional.empty()))
        .thenReturn(completedFuture(validationResult));
  }

  private void givenSuccessfulImport(final ExecutionPayloadEnvelope executionPayload) {
    when(forkChoice.onExecutionPayloadEnvelope(eq(executionPayload), eq(executionLayer), any()))
        .thenReturn(completedFuture(ExecutionPayloadImportResult.successful(executionPayload)));
  }

  private SafeFuture<InternalValidationResult> validateAndImport(
      final ExecutionPayloadEnvelope executionPayload) {
    return executionPayloadManager.validateAndImportExecutionPayload(executionPayload);
  }

  private SafeFuture<InternalValidationResult> validateAndImport(
      final ExecutionPayloadEnvelope executionPayload, final UInt64 arrivalTimestamp) {
    return executionPayloadManager.validateAndImportExecutionPayload(
        executionPayload, Optional.of(arrivalTimestamp));
  }

  private void validateAndImportAndJoin(
      final ExecutionPayloadEnvelope executionPayload, final UInt64 arrivalTimestamp) {
    SafeFutureAssert.safeJoin(validateAndImport(executionPayload, arrivalTimestamp));
  }

  /** Runs the import and hands the publisher fork choice was given the availability report. */
  private void reportAvailableToForkChoicePublisher(
      final ExecutionPayloadEnvelope executionPayload) {
    reportAvailableToForkChoicePublisher(executionPayload, Optional.empty());
  }

  /**
   * Runs the import and hands the publisher fork choice was given the availability report, moving
   * the clock on in between when a time is supplied, as verification and the data availability
   * check would.
   */
  private void reportAvailableToForkChoicePublisher(
      final ExecutionPayloadEnvelope executionPayload, final Optional<UInt64> timeWhenAvailable) {
    final ArgumentCaptor<Optional<ReceivedExecutionPayloadEventsChannel>> publisherCaptor =
        ArgumentCaptor.captor();
    when(forkChoice.onExecutionPayloadEnvelope(
            eq(executionPayload), eq(executionLayer), publisherCaptor.capture()))
        .thenReturn(completedFuture(ExecutionPayloadImportResult.successful(executionPayload)));

    final SafeFuture<ExecutionPayloadImportResult> resultFuture =
        executionPayloadManager.importExecutionPayload(executionPayload, false);
    asyncRunner.executeDueActions();
    assertThat(resultFuture).isCompleted();

    timeWhenAvailable.ifPresent(time -> when(store.getTimeInMillis()).thenReturn(time));
    publisherCaptor.getValue().orElseThrow().onExecutionPayloadAvailable(executionPayload);
  }

  private UInt64 payloadDueDeadlineMillis(final ExecutionPayloadEnvelope executionPayload) {
    final UInt64 slot = executionPayload.getSlot();
    return spec.computeTimeMillisAtSlot(slot, recentChainData.getGenesisTimeMillis())
        .plus(spec.getPayloadDueMillis(slot).orElseThrow());
  }

  /** An envelope naming the block which reveals a payload the block does not commit to. */
  private ExecutionPayloadEnvelope forgedExecutionPayload() {
    return dataStructureUtil.executionPayloadEnvelopeForBlock(
        block,
        dataStructureUtil.randomExecutionPayload(UInt64.valueOf(42)),
        committedExecutionRequests);
  }

  private ExecutionRequests defaultExecutionRequests(final UInt64 slot) {
    return SchemaDefinitionsGloas.required(spec.atSlot(slot).getSchemaDefinitions())
        .getExecutionRequestsSchema()
        .getDefault();
  }

  private void assertExecutionPayloadSeenBeforeDeadline(
      final ExecutionPayloadEnvelope executionPayload) {
    assertThat(
            executionPayloadManager.isExecutionPayloadSeenBeforeDeadline(
                executionPayload.getBeaconBlockRoot()))
        .isTrue();
  }

  private void assertExecutionPayloadNotSeenBeforeDeadline(
      final ExecutionPayloadEnvelope executionPayload) {
    assertThat(
            executionPayloadManager.isExecutionPayloadSeenBeforeDeadline(
                executionPayload.getBeaconBlockRoot()))
        .isFalse();
  }
}
