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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.spec.logic.common.statetransition.results.ExecutionPayloadImportResult.FailureReason;

/**
 * Tracks the attempts to import the execution payload envelope of a block which are in progress.
 *
 * <p>Several attempts for the same block can be in progress at once, for example an envelope
 * fetched by root and the same envelope arriving over gossip. The tracker does not deduplicate
 * them, it only tells what they come to: one successful attempt means the envelope is imported,
 * whatever the others come to, and the envelope has failed to import only once no attempt is in
 * progress any more.
 *
 * <p>Nothing is remembered once the last attempt for a block has ended: whether an envelope was
 * imported earlier is a question for the chain data.
 *
 * <p>An attempt is to be tracked from before it can have any visible effect, which for an envelope
 * arriving over gossip is before its validation marks the envelope as seen.
 */
class ExecutionPayloadImportTracker {

  private final Map<Bytes32, Imports> importsInProgress = new HashMap<>();

  /**
   * Starts tracking an attempt which may import the envelope of the block. It has to be ended by
   * calling one of the methods of the returned {@link Attempt}.
   */
  synchronized Attempt begin(final Bytes32 beaconBlockRoot) {
    final Imports imports = importsInProgress.computeIfAbsent(beaconBlockRoot, __ -> new Imports());
    imports.pendingAttempts++;
    return new Attempt(beaconBlockRoot, imports);
  }

  /**
   * What the attempts in progress to import the envelope of the block come to: known as soon as one
   * of them succeeds, and otherwise once the last of them has ended. {@link Outcome#NONE} at once
   * when no attempt is in progress.
   */
  synchronized SafeFuture<Outcome> getOutcome(final Bytes32 beaconBlockRoot) {
    final Imports imports = importsInProgress.get(beaconBlockRoot);
    return imports == null ? SafeFuture.completedFuture(Outcome.NONE) : imports.outcome;
  }

  private void end(
      final Bytes32 beaconBlockRoot,
      final Imports imports,
      final boolean imported,
      final Optional<FailureReason> failureReason) {
    final Outcome outcome;
    synchronized (this) {
      imports.pendingAttempts--;
      imports.imported |= imported;
      if (failureReason.isPresent()) {
        imports.failureReason = failureReason;
      }
      final boolean isLastAttempt = imports.pendingAttempts == 0;
      if (isLastAttempt) {
        importsInProgress.remove(beaconBlockRoot);
      }
      if (!imports.imported && !isLastAttempt) {
        return;
      }
      outcome =
          imports.imported
              ? Outcome.IMPORTED
              : imports.failureReason.map(Outcome::failed).orElse(Outcome.NONE);
    }
    // completed without holding the lock, as it runs whatever waits for the outcome
    imports.outcome.complete(outcome);
  }

  final class Attempt {
    private final Bytes32 beaconBlockRoot;
    private final Imports imports;
    private boolean ended = false;

    private Attempt(final Bytes32 beaconBlockRoot, final Imports imports) {
      this.beaconBlockRoot = beaconBlockRoot;
      this.imports = imports;
    }

    /** The envelope was imported. */
    void succeeded() {
      end(true, Optional.empty());
    }

    /** The import of the envelope failed. */
    void failed(final FailureReason failureReason) {
      end(false, Optional.of(failureReason));
    }

    /** The attempt ended without trying to import the envelope. */
    void endedWithoutImport() {
      end(false, Optional.empty());
    }

    private void end(final boolean imported, final Optional<FailureReason> failureReason) {
      synchronized (this) {
        if (ended) {
          return;
        }
        ended = true;
      }
      ExecutionPayloadImportTracker.this.end(beaconBlockRoot, imports, imported, failureReason);
    }
  }

  /**
   * @param imported whether an attempt imported the envelope
   * @param failureReason why the last attempt which failed did, when none imported the envelope
   */
  record Outcome(boolean imported, Optional<FailureReason> failureReason) {
    static final Outcome IMPORTED = new Outcome(true, Optional.empty());

    /** No attempt tried to import the envelope, which says nothing about earlier ones. */
    static final Outcome NONE = new Outcome(false, Optional.empty());

    static Outcome failed(final FailureReason failureReason) {
      return new Outcome(false, Optional.of(failureReason));
    }
  }

  // guarded by the tracker
  private static final class Imports {
    private final SafeFuture<Outcome> outcome = new SafeFuture<>();
    private int pendingAttempts = 0;
    private boolean imported = false;
    private Optional<FailureReason> failureReason = Optional.empty();
  }
}
