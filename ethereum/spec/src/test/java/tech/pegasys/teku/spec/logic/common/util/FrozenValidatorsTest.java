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

package tech.pegasys.teku.spec.logic.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.tree.FrozenRanges;
import tech.pegasys.teku.infrastructure.ssz.tree.FrozenSuperNode;
import tech.pegasys.teku.infrastructure.ssz.tree.SszSuperNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecContext;
import tech.pegasys.teku.spec.TestSpecInvocationContextProvider.SpecContext;
import tech.pegasys.teku.spec.config.SpecConfig;
import tech.pegasys.teku.spec.datastructures.interop.GenesisStateBuilder;
import tech.pegasys.teku.spec.datastructures.state.Validator;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconStateSchema;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.MutableBeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.common.BeaconStateFields;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.capella.MutableBeaconStateCapella;
import tech.pegasys.teku.spec.logic.common.statetransition.epoch.status.ValidatorStatus;
import tech.pegasys.teku.spec.logic.common.statetransition.epoch.status.ValidatorStatuses;

/**
 * Equivalence of the frozen-validator fast paths (state diff tier 3 attempt): the same epoch
 * transition on a state with and without frozen supernodes gives the same state, and the frozen
 * variant does not load the frozen supernodes.
 */
@TestSpecContext(milestone = {SpecMilestone.ELECTRA, SpecMilestone.FULU, SpecMilestone.GLOAS})
class FrozenValidatorsTest {

  /** Three supernodes of 256 validators; the first two hold only withdrawn validators. */
  private static final int VALIDATOR_COUNT = 768;

  private static final int WITHDRAWN = 512;
  private static final int EPOCH = 10;

  private static final Map<SpecMilestone, BeaconState> GENESIS_STATES = new ConcurrentHashMap<>();

  private final AtomicInteger loads = new AtomicInteger();
  private Spec spec;

  /**
   * The frozen range: whole supernodes of withdrawn validators. Bounded lists hold supernodes of
   * 256 validators from index 0; Gloas' progressive list holds element 0 alone, then supernodes of
   * 4, 16, 64 and 256 validators in its levels 1 to 4, and of 256 from level 5 (index 341) on.
   */
  private int frozenStart;

  private int frozenEnd;

  /** The frozen validators left once validator 3's supernode thaws. */
  private int frozenAfterThaw;

  private BeaconState plain;
  private BeaconState frozen;

  @BeforeEach
  void setUp(final SpecContext specContext) {
    spec = specContext.getSpec();
    final boolean progressive =
        spec.getGenesisSpec().getMilestone().isGreaterThanOrEqualTo(SpecMilestone.GLOAS);
    frozenStart = progressive ? 1 : 0;
    frozenEnd = progressive ? 341 : WITHDRAWN;
    frozenAfterThaw = progressive ? 336 : WITHDRAWN - 256;
    plain = withdrawnState(state -> {});
    frozen = freeze(plain);
  }

  private interface StateChange {
    void apply(MutableBeaconState state);
  }

  /** A state in {@link #EPOCH} whose first {@link #WITHDRAWN} validators are withdrawn. */
  private BeaconState withdrawnState(final StateChange change) {
    final BeaconState genesis =
        GENESIS_STATES.computeIfAbsent(
            spec.getGenesisSpec().getMilestone(),
            __ -> new GenesisStateBuilder().spec(spec).addMockValidators(VALIDATOR_COUNT).build());
    final BeaconState state =
        genesis.updated(
            mutable -> {
              mutable.setSlot(spec.computeStartSlotAtEpoch(UInt64.valueOf(EPOCH)).plus(1));
              for (int i = 0; i < WITHDRAWN; i++) {
                mutable
                    .getValidators()
                    .update(
                        i,
                        validator ->
                            validator
                                .withEffectiveBalance(UInt64.ZERO)
                                .withExitEpoch(UInt64.valueOf(2))
                                .withWithdrawableEpoch(UInt64.valueOf(5)));
                mutable.getBalances().setElement(i, UInt64.ZERO);
              }
              change.apply(mutable);
            });
    // Round trip so the validators are supernode backed, as a state loaded from disk
    return spec.deserializeBeaconState(state.sszSerialize());
  }

  /** {@code state} with every supernode of withdrawn validators frozen, loading in memory. */
  private BeaconState freeze(final BeaconState state) {
    final SszList<Validator> validators = state.getValidators();
    final TreeNode validatorsNode =
        FrozenRanges.replaceSupernodes(
            validators.getSchema(),
            validators.getBackingNode(),
            validators.size(),
            superNode ->
                allWithdrawn(superNode)
                    ? Optional.of(frozen(validators, superNode))
                    : Optional.empty());
    final BeaconStateSchema<?, ?> schema = state.getBeaconStateSchema();
    final long validatorsGIndex =
        schema.getChildGeneralizedIndex(schema.getFieldIndex(BeaconStateFields.VALIDATORS));
    final BeaconState result =
        schema.createFromBackingNode(
            state.getBackingNode().updated(validatorsGIndex, validatorsNode));
    assertThat(result.hashTreeRoot()).isEqualTo(state.hashTreeRoot());
    return result;
  }

  private static boolean allWithdrawn(final SszSuperNode superNode) {
    final Bytes data = superNode.getData();
    final int size = Validator.SSZ_SCHEMA.getSszFixedPartSize();
    if (data.isEmpty()) {
      return false;
    }
    for (int offset = 0; offset < data.size(); offset += size) {
      final Validator validator = Validator.SSZ_SCHEMA.sszDeserialize(data.slice(offset, size));
      if (!validator.getEffectiveBalance().isZero()
          || validator.getActivationEpoch().equals(SpecConfig.FAR_FUTURE_EPOCH)
          || validator.getExitEpoch().isGreaterThanOrEqualTo(EPOCH)
          || validator.getWithdrawableEpoch().isGreaterThan(EPOCH)) {
        return false;
      }
    }
    return true;
  }

  private FrozenSuperNode frozen(
      final SszList<Validator> validators, final SszSuperNode superNode) {
    final Bytes data = superNode.getData();
    return new FrozenSuperNode(
        superNode.getDepth(),
        validators.getSchema().getSupernodeElementTemplate(),
        data.size() / Validator.SSZ_SCHEMA.getSszFixedPartSize(),
        superNode.hashTreeRoot(),
        () -> {
          loads.incrementAndGet();
          return data;
        });
  }

  private BeaconState nextEpoch(final BeaconState state) throws Exception {
    return spec.processSlots(state, spec.computeStartSlotAtEpoch(UInt64.valueOf(EPOCH + 1)));
  }

  @TestTemplate
  void withdrawnSupernodesAreFrozen() {
    final FrozenRanges ranges = FrozenValidators.ranges(frozen.getValidators());

    assertThat(ranges.size()).isEqualTo(1);
    assertThat(List.of(ranges.start(0), ranges.end(0))).containsExactly(frozenStart, frozenEnd);
    assertThat(FrozenValidators.ranges(plain.getValidators()).isEmpty()).isTrue();
    assertThat(loads).hasValue(0);
  }

  @TestTemplate
  void epochTransitionIsEqualAndLoadsNothing() throws Exception {
    final BeaconState expected = nextEpoch(plain);

    final BeaconState actual = nextEpoch(frozen);

    assertThat(actual.hashTreeRoot()).isEqualTo(expected.hashTreeRoot());
    assertThat(loads).hasValue(0);
    assertThat(FrozenValidators.ranges(actual.getValidators()).coveredElements())
        .isEqualTo(frozenEnd - frozenStart);
  }

  @TestTemplate
  void frozenStatusesMatchTheReadStatuses() {
    final ValidatorStatuses expected =
        spec.atSlot(plain.getSlot()).getValidatorStatusFactory().createValidatorStatuses(plain);

    final ValidatorStatuses actual =
        spec.atSlot(frozen.getSlot()).getValidatorStatusFactory().createValidatorStatuses(frozen);

    assertThat(loads).hasValue(0);
    assertThat(actual.getTotalBalances().getCurrentEpochActiveValidators())
        .isEqualTo(expected.getTotalBalances().getCurrentEpochActiveValidators());
    assertThat(actual.getValidatorCount()).isEqualTo(expected.getValidatorCount());
    for (int i = 0; i < VALIDATOR_COUNT; i++) {
      final ValidatorStatus a = actual.getStatuses().get(i);
      final ValidatorStatus e = expected.getStatuses().get(i);
      assertThat(a.isFrozen()).isEqualTo(i >= frozenStart && i < frozenEnd);
      assertThat(a.isActiveInCurrentEpoch()).isEqualTo(e.isActiveInCurrentEpoch());
      assertThat(a.isActiveInPreviousEpoch()).isEqualTo(e.isActiveInPreviousEpoch());
      assertThat(a.isActiveInNextEpoch()).isEqualTo(e.isActiveInNextEpoch());
      assertThat(a.isWithdrawableInCurrentEpoch()).isEqualTo(e.isWithdrawableInCurrentEpoch());
      assertThat(a.isEligibleValidator()).isEqualTo(e.isEligibleValidator());
      assertThat(a.getCurrentEpochEffectiveBalance())
          .isEqualTo(e.getCurrentEpochEffectiveBalance());
    }
  }

  @TestTemplate
  void activeValidatorIndicesSkipFrozenRangesWithoutLoading() {
    final UInt64 epoch = spec.getCurrentEpoch(plain);

    assertThat(spec.getActiveValidatorIndices(frozen, epoch).toIntArray())
        .isEqualTo(spec.getActiveValidatorIndices(plain, epoch).toIntArray());
    assertThat(spec.getActiveValidatorIndices(frozen, epoch.plus(1)).toIntArray())
        .isEqualTo(spec.getActiveValidatorIndices(plain, epoch.plus(1)).toIntArray());
    assertThat(loads).hasValue(0);
    // Epochs before the previous one are not covered by the freeze rule: read as before
    assertThat(spec.getActiveValidatorIndices(frozen, UInt64.ONE).toIntArray())
        .isEqualTo(spec.getActiveValidatorIndices(plain, UInt64.ONE).toIntArray());
    assertThat(loads.get()).isPositive();
  }

  @TestTemplate
  void withdrawalSweepSkipsFrozenZeroBalancesWithoutLoading() {
    final BeaconState plainSweep =
        withdrawnState(
            state ->
                MutableBeaconStateCapella.required(state)
                    .setNextWithdrawalValidatorIndex(UInt64.valueOf(frozenEnd - 4)));
    final BeaconState frozenSweep = freeze(plainSweep);

    assertThat(spec.getExpectedWithdrawals(frozenSweep))
        .isEqualTo(spec.getExpectedWithdrawals(plainSweep));
    assertThat(loads).hasValue(0);
  }

  @TestTemplate
  void topUpOfAFrozenValidatorThawsItAndStaysEqual() throws Exception {
    final BeaconState plainTopUp =
        withdrawnState(state -> state.getBalances().setElement(3, UInt64.valueOf(2_000_000_000L)));
    final BeaconState frozenTopUp = freeze(plainTopUp);

    final BeaconState expected = nextEpoch(plainTopUp);
    final BeaconState actual = nextEpoch(frozenTopUp);

    assertThat(actual.hashTreeRoot()).isEqualTo(expected.hashTreeRoot());
    assertThat(actual.getValidators().get(3).getEffectiveBalance()).isNotEqualTo(UInt64.ZERO);
    assertThat(loads.get()).isPositive();
    assertThat(FrozenValidators.ranges(actual.getValidators()).coveredElements())
        .isEqualTo(frozenAfterThaw);
  }
}
