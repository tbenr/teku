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
import static tech.pegasys.teku.spec.logic.common.statetransition.results.ExecutionPayloadImportResult.FailureReason.FAILED_EXECUTION;
import static tech.pegasys.teku.spec.logic.common.statetransition.results.ExecutionPayloadImportResult.FailureReason.FAILED_VERIFICATION;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.statetransition.execution.ExecutionPayloadImportTracker.Attempt;
import tech.pegasys.teku.statetransition.execution.ExecutionPayloadImportTracker.Outcome;

class ExecutionPayloadImportTrackerTest {

  private static final Bytes32 ROOT = Bytes32.fromHexString("0x01");

  private final ExecutionPayloadImportTracker tracker = new ExecutionPayloadImportTracker();

  @Test
  void shouldHaveNoOutcomeForABlockWithoutAttemptsInProgress() {
    assertThat(tracker.getOutcome(ROOT)).isCompletedWithValue(Outcome.NONE);
  }

  @Test
  void shouldReportAnImportOnceItsAttemptSucceeded() {
    final Attempt attempt = tracker.begin(ROOT);
    final SafeFuture<Outcome> outcome = tracker.getOutcome(ROOT);
    assertThat(outcome).isNotDone();

    attempt.succeeded();

    assertThat(outcome).isCompletedWithValue(Outcome.IMPORTED);
  }

  @Test
  void shouldReportAFailureOnceItsAttemptFailed() {
    final Attempt attempt = tracker.begin(ROOT);
    final SafeFuture<Outcome> outcome = tracker.getOutcome(ROOT);

    attempt.failed(FAILED_EXECUTION);

    assertThat(outcome).isCompletedWithValue(Outcome.failed(FAILED_EXECUTION));
  }

  @Test
  void shouldRememberNothingOnceTheLastAttemptEnded() {
    tracker.begin(ROOT).succeeded();
    assertThat(tracker.getOutcome(ROOT)).isCompletedWithValue(Outcome.NONE);

    tracker.begin(ROOT).failed(FAILED_EXECUTION);
    assertThat(tracker.getOutcome(ROOT)).isCompletedWithValue(Outcome.NONE);
  }

  @Test
  void shouldReportAnImportWhenTheFirstOfOverlappingAttemptsFailsAndTheSecondSucceeds() {
    final Attempt first = tracker.begin(ROOT);
    final Attempt second = tracker.begin(ROOT);
    final SafeFuture<Outcome> outcome = tracker.getOutcome(ROOT);

    first.failed(FAILED_EXECUTION);
    // not failed for as long as another attempt is in progress
    assertThat(outcome).isNotDone();
    assertThat(tracker.getOutcome(ROOT)).isNotDone();

    second.succeeded();

    assertThat(outcome).isCompletedWithValue(Outcome.IMPORTED);
  }

  @Test
  void shouldReportAnImportWhenTheFirstOfOverlappingAttemptsSucceedsAndTheSecondFails() {
    final Attempt first = tracker.begin(ROOT);
    final Attempt second = tracker.begin(ROOT);
    final SafeFuture<Outcome> outcome = tracker.getOutcome(ROOT);

    first.succeeded();
    // known at once, whatever the other attempt comes to
    assertThat(outcome).isCompletedWithValue(Outcome.IMPORTED);
    assertThat(tracker.getOutcome(ROOT)).isCompletedWithValue(Outcome.IMPORTED);

    second.failed(FAILED_VERIFICATION);

    assertThat(outcome).isCompletedWithValue(Outcome.IMPORTED);
    assertThat(tracker.getOutcome(ROOT)).isCompletedWithValue(Outcome.NONE);
  }

  @Test
  void shouldReportTheLastFailureOnceAllOverlappingAttemptsFailed() {
    final Attempt first = tracker.begin(ROOT);
    final Attempt second = tracker.begin(ROOT);
    final SafeFuture<Outcome> outcome = tracker.getOutcome(ROOT);

    first.failed(FAILED_EXECUTION);
    second.failed(FAILED_VERIFICATION);

    assertThat(outcome).isCompletedWithValue(Outcome.failed(FAILED_VERIFICATION));
  }

  @Test
  void shouldReportTheFailureWhenAnotherAttemptEndsWithoutImportAfterwards() {
    final Attempt first = tracker.begin(ROOT);
    final Attempt second = tracker.begin(ROOT);
    final SafeFuture<Outcome> outcome = tracker.getOutcome(ROOT);

    first.failed(FAILED_EXECUTION);
    second.endedWithoutImport();

    assertThat(outcome).isCompletedWithValue(Outcome.failed(FAILED_EXECUTION));
  }

  @Test
  void shouldWaitForTheRetryOfAFailedImport() {
    tracker.begin(ROOT).failed(FAILED_EXECUTION);

    final Attempt retry = tracker.begin(ROOT);
    final SafeFuture<Outcome> outcome = tracker.getOutcome(ROOT);
    assertThat(outcome).isNotDone();

    retry.succeeded();

    assertThat(outcome).isCompletedWithValue(Outcome.IMPORTED);
  }

  @Test
  void shouldHaveNoOutcomeWhenAttemptsEndedWithoutImport() {
    final Attempt attempt = tracker.begin(ROOT);
    final SafeFuture<Outcome> outcome = tracker.getOutcome(ROOT);

    attempt.endedWithoutImport();

    // whoever was waiting is told
    assertThat(outcome).isCompletedWithValue(Outcome.NONE);
  }

  @Test
  void shouldKeepBlocksApart() {
    final Bytes32 otherRoot = Bytes32.fromHexString("0x02");
    final Attempt attempt = tracker.begin(ROOT);
    final Attempt otherAttempt = tracker.begin(otherRoot);
    final SafeFuture<Outcome> outcome = tracker.getOutcome(ROOT);
    final SafeFuture<Outcome> otherOutcome = tracker.getOutcome(otherRoot);

    otherAttempt.failed(FAILED_EXECUTION);

    assertThat(otherOutcome).isCompletedWithValue(Outcome.failed(FAILED_EXECUTION));
    assertThat(outcome).isNotDone();

    attempt.succeeded();

    assertThat(outcome).isCompletedWithValue(Outcome.IMPORTED);
  }

  @Test
  void shouldIgnoreAnAttemptEndingAgain() {
    final Attempt first = tracker.begin(ROOT);
    final Attempt second = tracker.begin(ROOT);
    final SafeFuture<Outcome> outcome = tracker.getOutcome(ROOT);

    first.endedWithoutImport();
    first.succeeded();
    first.endedWithoutImport();

    // the other attempt is still the one in progress
    assertThat(outcome).isNotDone();

    second.failed(FAILED_EXECUTION);

    assertThat(outcome).isCompletedWithValue(Outcome.failed(FAILED_EXECUTION));
  }
}
