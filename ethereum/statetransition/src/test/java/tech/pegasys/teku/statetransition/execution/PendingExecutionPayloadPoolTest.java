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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionRequests;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.statetransition.execution.PendingExecutionPayloadPool.PendingExecutionPayload;

class PendingExecutionPayloadPoolTest {

  private static final int MAX_UNCHECKED_ENVELOPES = 3;
  private static final long ENVELOPE_SIZE = 10;
  private static final long MAX_UNCHECKED_BYTES = 100;
  private static final UInt64 SLOT = UInt64.valueOf(42);
  private static final UInt64 ARRIVAL = UInt64.valueOf(7);

  private final Spec spec = TestSpecFactory.createMinimalGloas();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);

  private final ExecutionPayload payload = dataStructureUtil.randomExecutionPayload(SLOT);
  private final ExecutionRequests executionRequests =
      dataStructureUtil.randomExecutionRequests(SLOT);
  private final SignedBeaconBlock signedBlock =
      dataStructureUtil.randomSignedBeaconBlockCommittingTo(payload, executionRequests);
  private final BeaconBlock block = signedBlock.getMessage();
  private final ExecutionPayloadEnvelope committedEnvelope =
      dataStructureUtil.executionPayloadEnvelopeForBlock(signedBlock, payload, executionRequests);

  private long envelopeSize = ENVELOPE_SIZE;
  // run once, while the next envelope is being compared with its block
  private final AtomicReference<Runnable> whileComparing = new AtomicReference<>();
  private final PendingExecutionPayloadPool pool =
      new PendingExecutionPayloadPool(
          MAX_UNCHECKED_ENVELOPES,
          MAX_UNCHECKED_BYTES,
          __ -> envelopeSize,
          (envelope, envelopeBlock) -> {
            Optional.ofNullable(whileComparing.getAndSet(null)).ifPresent(Runnable::run);
            return PendingExecutionPayloadPool.findMismatch(envelope, envelopeBlock);
          });

  @BeforeEach
  void setUp() {
    pool.onSlot(SLOT);
  }

  @Test
  void shouldReturnTheEnvelopeHeldBeforeItsBlockWasKnown() {
    assertThat(pool.add(committedEnvelope, ARRIVAL)).isEmpty();
    assertThat(pool.getUncheckedCount()).isEqualTo(1);
    assertThat(pool.getUncheckedBytes()).isEqualTo(ENVELOPE_SIZE);

    assertThat(pool.remove(block))
        .contains(new PendingExecutionPayload(committedEnvelope, ARRIVAL));
    assertThat(pool.getUncheckedCount()).isZero();
    assertThat(pool.getUncheckedBytes()).isZero();
    assertThat(pool.remove(block)).isEmpty();
  }

  @Test
  void shouldReturnTheCommittedEnvelopeAmongOthersForTheSameBlock() {
    pool.add(forgedEnvelope(), ARRIVAL);
    pool.add(committedEnvelope, ARRIVAL);
    pool.add(forgedEnvelope(), ARRIVAL);

    assertThat(pool.remove(block).map(PendingExecutionPayload::executionPayload))
        .contains(committedEnvelope);
    assertThat(pool.getUncheckedCount()).isZero();
  }

  @Test
  void shouldReturnNothingWhenOnlyOtherEnvelopesAreHeldForTheBlock() {
    pool.add(forgedEnvelope(), ARRIVAL);

    assertThat(pool.remove(block)).isEmpty();
    assertThat(pool.getUncheckedCount()).isZero();
  }

  @Test
  void shouldKeepEnvelopesOfOtherBlocksWhenABlockIsRemoved() {
    pool.add(dataStructureUtil.randomExecutionPayloadEnvelope(SLOT), ARRIVAL);
    pool.add(committedEnvelope, ARRIVAL);

    assertThat(pool.remove(block)).isPresent();
    assertThat(pool.getUncheckedCount()).isEqualTo(1);
    assertThat(pool.getUncheckedBytes()).isEqualTo(ENVELOPE_SIZE);
  }

  @Test
  void shouldEvictTheOldestUncheckedEnvelopeWhenTooMany() {
    pool.add(committedEnvelope, ARRIVAL);
    for (int i = 0; i < MAX_UNCHECKED_ENVELOPES; i++) {
      pool.add(dataStructureUtil.randomExecutionPayloadEnvelope(SLOT), ARRIVAL);
    }

    assertThat(pool.getUncheckedCount()).isEqualTo(MAX_UNCHECKED_ENVELOPES);
    assertThat(pool.remove(block)).isEmpty();
  }

  @Test
  void shouldEvictTheOldestUncheckedEnvelopesWhenTooLarge() {
    envelopeSize = 40;
    pool.add(committedEnvelope, ARRIVAL);
    final ExecutionPayloadEnvelope second = dataStructureUtil.randomExecutionPayloadEnvelope(SLOT);
    pool.add(second, ARRIVAL);
    assertThat(pool.getUncheckedBytes()).isEqualTo(80);

    envelopeSize = 70;
    pool.add(dataStructureUtil.randomExecutionPayloadEnvelope(SLOT), ARRIVAL);

    // both had to go to make room
    assertThat(pool.getUncheckedCount()).isEqualTo(1);
    assertThat(pool.getUncheckedBytes()).isEqualTo(70);
    assertThat(pool.remove(block)).isEmpty();
  }

  @Test
  void shouldNotHoldAnEnvelopeLargerThanTheWholeBudget() {
    pool.add(committedEnvelope, ARRIVAL);

    envelopeSize = MAX_UNCHECKED_BYTES + 1;
    pool.add(dataStructureUtil.randomExecutionPayloadEnvelope(SLOT), ARRIVAL);

    // nothing was evicted for it either
    assertThat(pool.getUncheckedCount()).isEqualTo(1);
    assertThat(pool.remove(block)).isPresent();
  }

  @Test
  void shouldOnlyHoldEnvelopesForTheCurrentSlotAndTheNext() {
    pool.add(dataStructureUtil.randomExecutionPayloadEnvelope(SLOT.decrement()), ARRIVAL);
    pool.add(dataStructureUtil.randomExecutionPayloadEnvelope(SLOT.plus(2)), ARRIVAL);
    assertThat(pool.getUncheckedCount()).isZero();

    pool.add(dataStructureUtil.randomExecutionPayloadEnvelope(SLOT), ARRIVAL);
    pool.add(dataStructureUtil.randomExecutionPayloadEnvelope(SLOT.increment()), ARRIVAL);
    assertThat(pool.getUncheckedCount()).isEqualTo(2);
  }

  @Test
  void shouldDropEverythingFromEarlierSlotsWhenTheSlotChanges() {
    pool.add(dataStructureUtil.randomExecutionPayloadEnvelope(SLOT), ARRIVAL);
    pool.add(dataStructureUtil.randomExecutionPayloadEnvelope(SLOT.increment()), ARRIVAL);
    pool.onBlockValidated(block);
    pool.add(committedEnvelope, ARRIVAL);
    assertThat(pool.getCheckedCount()).isEqualTo(1);

    pool.onSlot(SLOT.increment());

    assertThat(pool.getUncheckedCount()).isEqualTo(1);
    assertThat(pool.getUncheckedBytes()).isEqualTo(ENVELOPE_SIZE);
    assertThat(pool.getCheckedCount()).isZero();
    assertThat(pool.remove(block)).isEmpty();
    // the block is no longer known as validated, so an envelope for it is not compared with it
    assertThat(pool.add(forgedEnvelope(), ARRIVAL)).isEmpty();
  }

  @Test
  void shouldRejectAnEnvelopeAValidatedBlockDoesNotCommitTo() {
    pool.onBlockValidated(block);

    assertThat(pool.add(forgedEnvelope(), ARRIVAL))
        .hasValueSatisfying(mismatch -> assertThat(mismatch).contains("payload root"));
    assertThat(pool.getUncheckedCount()).isZero();
    assertThat(pool.getCheckedCount()).isZero();
  }

  @Test
  void shouldHoldTheEnvelopeAValidatedBlockCommitsToWhereOthersCannotEvictIt() {
    pool.onBlockValidated(block);

    assertThat(pool.add(committedEnvelope, ARRIVAL)).isEmpty();
    assertThat(pool.getCheckedCount()).isEqualTo(1);
    assertThat(pool.getUncheckedCount()).isZero();

    for (int i = 0; i < MAX_UNCHECKED_ENVELOPES + 1; i++) {
      pool.add(dataStructureUtil.randomExecutionPayloadEnvelope(SLOT), ARRIVAL);
    }

    assertThat(pool.remove(block))
        .contains(new PendingExecutionPayload(committedEnvelope, ARRIVAL));
    assertThat(pool.getCheckedCount()).isZero();
  }

  @Test
  void shouldKeepTheFirstArrivalOfTheCommittedEnvelope() {
    pool.onBlockValidated(block);
    pool.add(committedEnvelope, ARRIVAL);
    pool.add(committedEnvelope, ARRIVAL.plus(100));

    assertThat(pool.remove(block).map(PendingExecutionPayload::arrivalTimestamp)).contains(ARRIVAL);
  }

  @Test
  void shouldCheckTheEnvelopesAlreadyHeldWhenTheirBlockIsValidated() {
    pool.add(forgedEnvelope(), ARRIVAL);
    pool.add(committedEnvelope, ARRIVAL);
    final ExecutionPayloadEnvelope otherBlockEnvelope =
        dataStructureUtil.randomExecutionPayloadEnvelope(SLOT);
    pool.add(otherBlockEnvelope, ARRIVAL);

    pool.onBlockValidated(block);

    // the committed envelope is now checked, the forged one is gone and the other block's stays
    assertThat(pool.getCheckedCount()).isEqualTo(1);
    assertThat(pool.getUncheckedCount()).isEqualTo(1);
    assertThat(pool.getUncheckedBytes()).isEqualTo(ENVELOPE_SIZE);
    assertThat(pool.remove(block).map(PendingExecutionPayload::executionPayload))
        .contains(committedEnvelope);
  }

  @Test
  void shouldHandOverTheEnvelopeWhenItsBlockIsImportedWhileItIsBeingChecked() {
    pool.add(forgedEnvelope(), ARRIVAL);
    pool.add(committedEnvelope, ARRIVAL);

    final AtomicReference<Optional<PendingExecutionPayload>> handedToImport =
        new AtomicReference<>(Optional.empty());
    whileComparing.set(() -> handedToImport.set(pool.remove(block)));
    pool.onBlockValidated(block);

    assertThat(handedToImport.get())
        .contains(new PendingExecutionPayload(committedEnvelope, ARRIVAL));
    // and nothing is left behind for the imported block
    assertThat(pool.getCheckedCount()).isZero();
    assertThat(pool.getUncheckedCount()).isZero();
    assertThat(pool.getUncheckedBytes()).isZero();
    assertThat(pool.add(forgedEnvelope(), ARRIVAL)).isEmpty();
  }

  @Test
  void shouldHandOverAnEnvelopeOfAValidatedBlockWhenTheBlockIsImportedWhileItIsBeingChecked() {
    pool.onBlockValidated(block);

    final AtomicReference<Optional<PendingExecutionPayload>> handedToImport =
        new AtomicReference<>(Optional.empty());
    whileComparing.set(() -> handedToImport.set(pool.remove(block)));
    assertThat(pool.add(committedEnvelope, ARRIVAL)).isEmpty();

    assertThat(handedToImport.get())
        .contains(new PendingExecutionPayload(committedEnvelope, ARRIVAL));
    // and nothing is left behind for the imported block
    assertThat(pool.getCheckedCount()).isZero();
    assertThat(pool.getUncheckedCount()).isZero();
    assertThat(pool.remove(block)).isEmpty();
  }

  @Test
  void shouldNotHandOverAnotherEnvelopeWhenTheBlockIsImportedWhileItIsBeingChecked() {
    pool.onBlockValidated(block);

    final AtomicReference<Optional<PendingExecutionPayload>> handedToImport =
        new AtomicReference<>();
    whileComparing.set(() -> handedToImport.set(pool.remove(block)));

    assertThat(pool.add(forgedEnvelope(), ARRIVAL)).isPresent();
    assertThat(handedToImport.get()).isEmpty();
    assertThat(pool.getCheckedCount()).isZero();
  }

  @Test
  void shouldNotKeepAnEnvelopeWhoseSlotPassedWhileItWasBeingChecked() {
    pool.onBlockValidated(block);

    whileComparing.set(() -> pool.onSlot(SLOT.plus(1)));
    assertThat(pool.add(committedEnvelope, ARRIVAL)).isEmpty();

    assertThat(pool.getCheckedCount()).isZero();
    assertThat(pool.remove(block)).isEmpty();
  }

  @Test
  void shouldKeepTheEarlierArrivalWhenTheEnvelopeArrivesAgainWhileItIsBeingChecked() {
    pool.add(committedEnvelope, ARRIVAL);

    whileComparing.set(() -> pool.add(committedEnvelope, ARRIVAL.plus(100)));
    pool.onBlockValidated(block);

    assertThat(pool.getCheckedCount()).isEqualTo(1);
    assertThat(pool.getUncheckedCount()).isZero();
    assertThat(pool.remove(block))
        .contains(new PendingExecutionPayload(committedEnvelope, ARRIVAL));
  }

  @Test
  void shouldNotTrackValidatedBlocksBeyondTheLimit() {
    for (int i = 0; i < PendingExecutionPayloadPool.MAX_VALIDATED_BLOCKS; i++) {
      pool.onBlockValidated(
          dataStructureUtil
              .randomSignedBeaconBlockCommittingTo(
                  dataStructureUtil.randomExecutionPayload(SLOT),
                  dataStructureUtil.randomExecutionRequests(SLOT))
              .getMessage());
    }
    pool.onBlockValidated(block);

    // not compared with the block, so held unchecked like any other
    assertThat(pool.add(forgedEnvelope(), ARRIVAL)).isEmpty();
    assertThat(pool.getUncheckedCount()).isEqualTo(1);
  }

  @Test
  void shouldIgnoreValidatedBlocksWithoutABid() {
    final Spec fuluSpec = TestSpecFactory.createMinimalFulu();
    final BeaconBlock fuluBlock = new DataStructureUtil(fuluSpec).randomBeaconBlock(SLOT);

    pool.onBlockValidated(fuluBlock);

    assertThat(pool.remove(fuluBlock)).isEmpty();
  }

  /** An envelope naming the block which reveals a payload the block does not commit to. */
  private ExecutionPayloadEnvelope forgedEnvelope() {
    return dataStructureUtil.executionPayloadEnvelopeForBlock(
        signedBlock, dataStructureUtil.randomExecutionPayload(SLOT), executionRequests);
  }
}
