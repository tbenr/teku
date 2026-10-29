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

package tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Consumer;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.metrics.StubMetricsSystem;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.tree.FrozenRanges;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.state.Validator;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.storage.server.kvstore.KvStoreAccessor;
import tech.pegasys.teku.storage.server.kvstore.MockKvStoreInstance;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.CombinedKvStoreDao;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao.HotUpdater;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.V4FinalizedStateSnapshotStorageLogic;
import tech.pegasys.teku.storage.server.kvstore.schema.V6SchemaCombinedSnapshot;

class ExitedValidatorFreezerTest {

  private static final int VALIDATOR_COUNT = 600;
  private static final int EXITED = 256;
  private static final int EPOCH = 100;

  private final Spec spec = TestSpecFactory.createMinimalElectra();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final KvStoreCombinedDao dao = createDao(spec);
  private final StubMetricsSystem metricsSystem = new StubMetricsSystem();
  private final StateDiffDagConfig config = StateDiffDagConfig.prune().withFreezeExited(true);
  private final StateDiffDag dag = new StateDiffDag(spec, config, metricsSystem);

  /** A state at {@link #EPOCH} whose first {@link #EXITED} validators are withdrawn. */
  private BeaconState stateWithExitedValidators(final int withdrawableEpoch) {
    final BeaconState random = dataStructureUtil.randomBeaconState(VALIDATOR_COUNT);
    final BeaconState state =
        random.updated(
            mutable -> {
              mutable.setSlot(spec.computeStartSlotAtEpoch(UInt64.valueOf(EPOCH)));
              for (int i = 0; i < EXITED; i++) {
                mutable
                    .getValidators()
                    .update(
                        i,
                        validator ->
                            validator
                                .withEffectiveBalance(UInt64.ZERO)
                                .withActivationEpoch(UInt64.ONE)
                                .withExitEpoch(UInt64.valueOf(withdrawableEpoch - 10))
                                .withWithdrawableEpoch(UInt64.valueOf(withdrawableEpoch)));
                mutable.getBalances().setElement(i, UInt64.ZERO);
              }
            });
    // Round trip so the validators are supernode backed, as a state loaded from disk
    return spec.deserializeBeaconState(state.sszSerialize());
  }

  @Test
  void reconstructionFreezesWithdrawnSupernodesAndKeepsTheState() {
    final BeaconState state = stateWithExitedValidators(EPOCH - 5);
    final Bytes32 root = dataStructureUtil.randomBytes32();
    update(tx -> tx.writeNode(root, state, epoch(EPOCH), epoch(EPOCH), List.of(), true));

    final BeaconState reconstructed = uncachedDag().reconstruct(dao, root).orElseThrow();

    assertThat(reconstructed.hashTreeRoot()).isEqualTo(state.hashTreeRoot());
    final FrozenRanges ranges = frozenRanges(reconstructed);
    assertThat(ranges.size()).isEqualTo(1);
    assertThat(List.of(ranges.start(0), ranges.end(0))).containsExactly(0, EXITED);
    for (final int index : List.of(0, 17, EXITED - 1, EXITED, VALIDATOR_COUNT - 1)) {
      assertThat(reconstructed.getValidators().get(index))
          .isEqualTo(state.getValidators().get(index));
    }
    assertThat(reconstructed).isEqualTo(state);
  }

  @Test
  void supernodesWithdrawableAfterTheNodeEpochAreNotFrozen() {
    final BeaconState state = stateWithExitedValidators(EPOCH + 1);
    final Bytes32 root = dataStructureUtil.randomBytes32();
    update(tx -> tx.writeNode(root, state, epoch(EPOCH), epoch(EPOCH), List.of(), true));

    final BeaconState reconstructed = uncachedDag().reconstruct(dao, root).orElseThrow();

    assertThat(reconstructed.hashTreeRoot()).isEqualTo(state.hashTreeRoot());
    assertThat(frozenRanges(reconstructed).isEmpty()).isTrue();
  }

  @Test
  void stateDerivedFromAFrozenStateIsWrittenAndReconstructed() {
    final BeaconState state = stateWithExitedValidators(EPOCH - 5);
    final Bytes32 root = dataStructureUtil.randomBytes32();
    update(tx -> tx.writeNode(root, state, epoch(EPOCH), epoch(EPOCH), List.of(), true));
    final BeaconState frozen = dag.reconstructAndCache(dao, root).orElseThrow();
    // A top-up of a frozen validator thaws its supernode; another supernode changes too
    final BeaconState next =
        frozen.updated(
            mutable -> {
              mutable.setSlot(spec.computeStartSlotAtEpoch(UInt64.valueOf(EPOCH + 1)));
              mutable
                  .getValidators()
                  .update(3, validator -> validator.withEffectiveBalance(UInt64.ONE));
              mutable
                  .getValidators()
                  .update(400, validator -> validator.withEffectiveBalance(UInt64.ONE));
            });
    final BeaconState expected =
        state.updated(
            mutable -> {
              mutable.setSlot(spec.computeStartSlotAtEpoch(UInt64.valueOf(EPOCH + 1)));
              mutable
                  .getValidators()
                  .update(3, validator -> validator.withEffectiveBalance(UInt64.ONE));
              mutable
                  .getValidators()
                  .update(400, validator -> validator.withEffectiveBalance(UInt64.ONE));
            });
    assertThat(frozenRanges(next).isEmpty()).isTrue();
    assertThat(next.hashTreeRoot()).isEqualTo(expected.hashTreeRoot());

    final Bytes32 nextRoot = dataStructureUtil.randomBytes32();
    update(
        tx ->
            tx.writeNode(nextRoot, next, epoch(EPOCH + 1), epoch(EPOCH + 1), List.of(root), false));

    assertThat(
            StateDiffNodeRecordCodec.decode(dao.getStateDiffNode(nextRoot).orElseThrow())
                .snapshot())
        .isFalse();
    assertThat(uncachedDag().reconstruct(dao, nextRoot).orElseThrow()).isEqualTo(expected);
    assertThat(dag.reconstruct(dao, nextRoot).orElseThrow()).isEqualTo(expected);
  }

  @Test
  void eligibilityNeedsEveryValidatorWithdrawnWithZeroEffectiveBalance() {
    final Validator withdrawn =
        dataStructureUtil
            .randomValidator()
            .withEffectiveBalance(UInt64.ZERO)
            .withActivationEpoch(UInt64.ONE)
            .withExitEpoch(UInt64.valueOf(8))
            .withWithdrawableEpoch(UInt64.valueOf(20));
    final Validator active = withdrawn.withEffectiveBalance(UInt64.valueOf(32_000_000_000L));
    final Validator exiting = withdrawn.withExitEpoch(UInt64.valueOf(30));

    assertThat(eligibleFrom(withdrawn, withdrawn)).isEqualTo(20);
    assertThat(eligibleFrom(withdrawn, exiting)).isEqualTo(31);
    assertThat(eligibleFrom(withdrawn, active)).isEqualTo(Long.MAX_VALUE);
    assertThat(
            eligibleFrom(
                withdrawn,
                withdrawn.withActivationEpoch(UInt64.MAX_VALUE).withExitEpoch(UInt64.MAX_VALUE)))
        .isEqualTo(Long.MAX_VALUE);
  }

  private static long eligibleFrom(final Validator... validators) {
    Bytes ssz = Bytes.EMPTY;
    for (final Validator validator : validators) {
      ssz = Bytes.wrap(ssz, validator.sszSerialize());
    }
    return ExitedValidatorFreezer.eligibleFromEpoch(ssz);
  }

  private static FrozenRanges frozenRanges(final BeaconState state) {
    final SszList<Validator> validators = state.getValidators();
    return FrozenRanges.collect(
        validators.getSchema(), validators.getBackingNode(), validators.size());
  }

  private StateDiffDag uncachedDag() {
    return new StateDiffDag(spec, config, metricsSystem);
  }

  private void update(final Consumer<StateDiffDagTransaction> action) {
    try (final HotUpdater updater = dao.hotUpdater()) {
      final StateDiffDagTransaction transaction = dag.begin(dao, updater);
      action.accept(transaction);
      transaction.commit();
      updater.commit();
    }
  }

  private static UInt64 epoch(final int epoch) {
    return UInt64.valueOf(epoch);
  }

  private static KvStoreCombinedDao createDao(final Spec spec) {
    final V6SchemaCombinedSnapshot schema = V6SchemaCombinedSnapshot.createV6(spec);
    final KvStoreAccessor db =
        MockKvStoreInstance.createEmpty(schema.getAllColumns(), schema.getAllVariables());
    return new CombinedKvStoreDao<>(
        db, schema, new V4FinalizedStateSnapshotStorageLogic<>(1), new StubMetricsSystem());
  }
}
