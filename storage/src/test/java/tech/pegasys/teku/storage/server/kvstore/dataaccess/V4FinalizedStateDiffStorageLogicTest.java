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

package tech.pegasys.teku.storage.server.kvstore.dataaccess;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.storage.server.kvstore.KvStoreAccessor;
import tech.pegasys.teku.storage.server.kvstore.KvStoreAccessor.KvStoreTransaction;
import tech.pegasys.teku.storage.server.kvstore.MockKvStoreInstance;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.V4FinalizedStateStorageLogic.FinalizedStateUpdater;
import tech.pegasys.teku.storage.server.kvstore.schema.SchemaCombinedDiffState;
import tech.pegasys.teku.storage.server.kvstore.schema.V6SchemaCombinedDiffState;

class V4FinalizedStateDiffStorageLogicTest {

  private final Spec spec = TestSpecFactory.createDefault();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final V6SchemaCombinedDiffState schema = new V6SchemaCombinedDiffState(spec);
  private final KvStoreAccessor db =
      MockKvStoreInstance.createEmpty(schema.getAllColumns(), schema.getAllVariables());

  private final V4FinalizedStateDiffStorageLogic logic =
      V4FinalizedStateDiffStorageLogic.create(spec);

  @Test
  void shouldBootstrapAndReconstructState() {
    final BeaconState state = stateAtEpoch(3);

    storeState(state);

    assertThat(logic.getLatestFinalizedState(db, schema)).contains(state);
  }

  @Test
  void shouldReconstructSequentialStatesAcrossTransactions() {
    final BeaconState state3 = stateAtEpoch(3);
    final BeaconState state4 = stateAtEpoch(4);
    final BeaconState state5 = stateAtEpoch(5);

    storeState(state3);
    storeState(state4);
    storeState(state5);

    assertThat(logic.getLatestFinalizedState(db, schema)).contains(state5);
  }

  @Test
  void shouldStoreMultipleStatesInSingleTransaction() {
    final BeaconState state3 = stateAtEpoch(3);
    final BeaconState state4 = stateAtEpoch(4);
    final BeaconState state5 = stateAtEpoch(5);

    try (final KvStoreTransaction transaction = db.startTransaction()) {
      final FinalizedStateUpdater<SchemaCombinedDiffState> updater = logic.updater();
      updater.addFinalizedState(db, transaction, schema, state3);
      updater.addFinalizedState(db, transaction, schema, state4);
      updater.addFinalizedState(db, transaction, schema, state5);
      transaction.commit();
    }

    assertThat(logic.getLatestFinalizedState(db, schema)).contains(state5);
  }

  @Test
  void shouldGetLatestAvailableFinalizedState() {
    final BeaconState state = stateAtEpoch(3);
    storeState(state);

    final UInt64 stateSlot = state.getSlot();

    // Query at state's slot returns the state
    assertThat(logic.getLatestAvailableFinalizedState(db, schema, stateSlot)).contains(state);

    // Query beyond state's slot returns the state (floor)
    assertThat(logic.getLatestAvailableFinalizedState(db, schema, stateSlot.plus(100)))
        .contains(state);
  }

  @Test
  void shouldReturnEmptyWhenNoStateStored() {
    assertThat(logic.getLatestFinalizedState(db, schema)).isEmpty();
    assertThat(logic.getLatestAvailableFinalizedState(db, schema, UInt64.valueOf(1000))).isEmpty();
  }

  @Test
  void canReconstructLatestFinalizedState_returnsTrue() {
    assertThat(logic.canReconstructLatestFinalizedState()).isTrue();
  }

  @Test
  void shouldGetEarliestAvailableFinalizedStateSlot() {
    assertThat(logic.getEarliestAvailableFinalizedStateSlot(db, schema)).isEmpty();

    final BeaconState state = stateAtEpoch(3);
    storeState(state);

    // Bootstrap creates level-0 snapshot at epoch 0, so earliest slot is epoch 0's start slot
    assertThat(logic.getEarliestAvailableFinalizedStateSlot(db, schema))
        .contains(spec.computeStartSlotAtEpoch(UInt64.ZERO));
  }

  @Test
  void shouldPersistCacheAcrossUpdaterInstances() {
    final BeaconState state3 = stateAtEpoch(3);
    final BeaconState state4 = stateAtEpoch(4);
    final BeaconState state5 = stateAtEpoch(5);

    // Each storeState creates a NEW updater via logic.updater()
    // The cache from state3's updater should be available to state4's updater, etc.
    storeState(state3);
    storeState(state4);
    storeState(state5);

    // All states should reconstruct correctly
    assertThat(logic.getLatestFinalizedState(db, schema)).contains(state5);
    assertThat(logic.getLatestAvailableFinalizedState(db, schema, state3.getSlot()))
        .contains(state3);
  }

  @Test
  void shouldDeleteFinalizedState() {
    final BeaconState state3 = stateAtEpoch(3);
    final BeaconState state4 = stateAtEpoch(4);

    storeState(state3);
    storeState(state4);

    // Delete epoch 4's state entries
    try (final KvStoreTransaction transaction = db.startTransaction()) {
      final FinalizedStateUpdater<SchemaCombinedDiffState> updater = logic.updater();
      updater.deleteFinalizedState(transaction, schema, state4.getSlot());
      transaction.commit();
    }

    // Latest should now be epoch 3
    assertThat(logic.getLatestFinalizedState(db, schema)).contains(state3);
  }

  @Test
  void shouldCacheHitOnGetLatestFinalizedStateAfterStore() {
    final BeaconState state3 = stateAtEpoch(3);
    storeState(state3);

    // After commit(), cachedState holds epoch 3's SSZ bytes.
    // getLatestFinalizedState should return the correct state via cache hit.
    final BeaconState first = logic.getLatestFinalizedState(db, schema).orElseThrow();
    assertThat(first).isEqualTo(state3);

    // Calling again should also succeed (cache still valid, epoch unchanged)
    final BeaconState second = logic.getLatestFinalizedState(db, schema).orElseThrow();
    assertThat(second).isEqualTo(state3);
  }

  @Test
  void shouldCacheHitAfterSequentialStores() {
    final BeaconState state3 = stateAtEpoch(3);
    final BeaconState state4 = stateAtEpoch(4);

    storeState(state3);
    storeState(state4);

    // Cache should now hold epoch 4's SSZ. getLatestFinalizedState should cache-hit.
    final BeaconState result = logic.getLatestFinalizedState(db, schema).orElseThrow();
    assertThat(result).isEqualTo(state4);
  }

  @Test
  void shouldBootstrapAtEpochZero() {
    // Epoch 0 is aligned with level-0, so bootstrap produces just a snapshot
    final BeaconState state = stateAtEpoch(0);

    storeState(state);

    assertThat(logic.getLatestFinalizedState(db, schema)).contains(state);
    assertThat(logic.getEarliestAvailableFinalizedStateSlot(db, schema))
        .contains(spec.computeStartSlotAtEpoch(UInt64.ZERO));
  }

  @Test
  void shouldFindStateStoredAtCoarserHierarchyLevel() {
    // Epoch 16 is aligned to level 5 (period 16), so it is stored only at level 5,
    // not level 6. Verify it can still be found by getLatestAvailableFinalizedState.
    BeaconState epoch16State = null;
    for (long epoch = 1; epoch <= 16; epoch++) {
      final BeaconState state = stateAtEpoch(epoch);
      storeState(state);
      if (epoch == 16) {
        epoch16State = state;
      }
    }

    assertThat(logic.getLatestAvailableFinalizedState(db, schema, epoch16State.getSlot()))
        .contains(epoch16State);
  }

  private BeaconState stateAtEpoch(final long epoch) {
    return dataStructureUtil.randomBeaconState(spec.computeStartSlotAtEpoch(UInt64.valueOf(epoch)));
  }

  private void storeState(final BeaconState state) {
    try (final KvStoreTransaction transaction = db.startTransaction()) {
      final FinalizedStateUpdater<SchemaCombinedDiffState> updater = logic.updater();
      updater.addFinalizedState(db, transaction, schema, state);
      transaction.commit();
      updater.commit();
    }
  }
}
