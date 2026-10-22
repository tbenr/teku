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
import static tech.pegasys.teku.spec.SpecMilestone.ELECTRA;
import static tech.pegasys.teku.spec.SpecMilestone.FULU;
import static tech.pegasys.teku.spec.SpecMilestone.GLOAS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszByte;
import tech.pegasys.teku.infrastructure.ssz.tree.GIndexUtil;
import tech.pegasys.teku.infrastructure.ssz.tree.GIndexUtil.NodeRelation;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.statediff.SupernodeSource;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.DeltaEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.SupernodeEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffCodec;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecContext;
import tech.pegasys.teku.spec.TestSpecInvocationContextProvider.SpecContext;
import tech.pegasys.teku.spec.datastructures.state.Validator;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconStateSchema;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.common.BeaconStateFields;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.altair.MutableBeaconStateAltair;
import tech.pegasys.teku.spec.generator.ChainBuilder;
import tech.pegasys.teku.spec.util.DataStructureUtil;

@TestSpecContext(milestone = {ELECTRA, FULU, GLOAS})
class BeaconStateDiffFuzzTest {

  private static final int VALIDATOR_COUNT = 64;
  private static final int NUM_TRIALS = 50;
  private static final int MAX_MUTATIONS_PER_TRIAL = 20;
  private static final int CHAIN_LENGTH = 10;
  private static final int MAX_EPOCH_TRANSITION_LEAVES = 1000;

  private final Map<Bytes32, Bytes> supernodes = new HashMap<>();
  private final SupernodeSource supernodeSource = hash -> Optional.ofNullable(supernodes.get(hash));

  private Spec spec;
  private DataStructureUtil dataStructureUtil;
  private BeaconStateDiffSupport support;

  @BeforeEach
  void setUp(final SpecContext specContext) {
    spec = specContext.getSpec();
    dataStructureUtil = specContext.getDataStructureUtil();
    support = new BeaconStateDiffSupport(spec);
  }

  @TestTemplate
  void randomMutationsRoundTrip() {
    final Random rng = new Random(12345);
    int trialsWithSupernodes = 0;
    for (int trial = 0; trial < NUM_TRIALS; trial++) {
      final BeaconState base = dataStructureUtil.randomBeaconState(VALIDATOR_COUNT);
      final BeaconState target = applyRandomMutations(base, rng);

      final TreeDiff diff = diff(base, target);
      if (!diff.supernodes().isEmpty()) {
        trialsWithSupernodes++;
      }
      final TreeNode reconstructed =
          support
              .applierFor(base.getSlot(), supernodeSource)
              .apply(base.getBackingNode(), roundTripCodec(diff));

      assertThat(reconstructed.hashTreeRoot())
          .as("Trial %d: hash tree root", trial)
          .isEqualTo(target.hashTreeRoot());
      assertSameState(reconstructed, target, "Trial " + trial);
    }
    // Validator mutations must reach the applier as supernode entries, not as leaves
    assertThat(trialsWithSupernodes).isPositive();
    assertThat(supernodes).isNotEmpty();
  }

  @TestTemplate
  void snapshotFromDefaultTreeRoundTrip() {
    final Random rng = new Random(23456);
    final BeaconState state =
        applyRandomMutations(dataStructureUtil.randomBeaconState(VALIDATOR_COUNT), rng);
    final BeaconStateSchema<?, ?> schema = support.schemaFor(state.getSlot());

    final TreeDiff snapshot =
        support
            .differFor(state.getSlot())
            .diff(schema.getDefaultTree(), state.getBackingNode(), supernodes::put);
    // 64 validators fill supernode-packed chunks, so the snapshot must carry supernode entries
    assertThat(snapshot.supernodes()).isNotEmpty();
    assertThat(supernodes.keySet())
        .containsAll(snapshot.supernodes().stream().map(SupernodeEntry::hash).toList());
    final TreeNode reconstructed =
        support
            .applierFor(state.getSlot(), supernodeSource)
            .apply(schema.getDefaultTree(), roundTripCodec(snapshot));

    assertThat(reconstructed.hashTreeRoot()).isEqualTo(state.hashTreeRoot());
    assertSameState(reconstructed, state, "Snapshot");
  }

  @TestTemplate
  void chainOfDiffsRoundTrip() {
    final Random rng = new Random(34567);
    final List<BeaconState> states = new ArrayList<>();
    states.add(dataStructureUtil.randomBeaconState(VALIDATOR_COUNT));
    for (int i = 1; i < CHAIN_LENGTH; i++) {
      states.add(applyRandomMutations(states.get(i - 1), rng));
    }
    final BeaconState first = states.getFirst();
    final BeaconStateSchema<?, ?> schema = support.schemaFor(first.getSlot());

    // The chain starts from a snapshot, so every diff is applied to a tree that shares no node
    // instances with the original states, as it would be when rebuilt from disk
    final List<Bytes> encodedDiffs = new ArrayList<>();
    encodedDiffs.add(
        TreeDiffCodec.serialize(
            support
                .differFor(first.getSlot())
                .diff(schema.getDefaultTree(), first.getBackingNode(), supernodes::put)));
    for (int i = 1; i < CHAIN_LENGTH; i++) {
      encodedDiffs.add(TreeDiffCodec.serialize(diff(states.get(i - 1), states.get(i))));
    }

    TreeNode current = schema.getDefaultTree();
    for (int i = 0; i < CHAIN_LENGTH; i++) {
      final BeaconState expected = states.get(i);
      current =
          support
              .applierFor(expected.getSlot(), supernodeSource)
              .apply(current, TreeDiffCodec.deserialize(encodedDiffs.get(i)));
      assertThat(current.hashTreeRoot())
          .as("Chain state %d: hash tree root", i)
          .isEqualTo(expected.hashTreeRoot());
      assertSameState(current, expected, "Chain state " + i);
    }
  }

  @TestTemplate
  void epochTransitionProducesOnlyDeltaAndSmallSections() throws Exception {
    final ChainBuilder chainBuilder = ChainBuilder.create(spec);
    chainBuilder.generateGenesis();
    final int slotsPerEpoch = spec.getGenesisSpecConfig().getSlotsPerEpoch();
    // Past the genesis epoch, so the epoch transition applies rewards and penalties
    chainBuilder.generateBlocksUpToSlot(3L * slotsPerEpoch - 1);
    final BeaconState base = chainBuilder.getLatestBlockAndState().getState();
    final UInt64 nextEpochStart =
        spec.computeStartSlotAtEpoch(spec.computeEpochAtSlot(base.getSlot()).plus(1));
    final BeaconState target = spec.processSlots(base, nextEpochStart);

    final TreeDiff diff = diff(base, target);

    final BeaconStateSchema<?, ?> schema = support.schemaFor(base.getSlot());
    final int balancesField = schema.getFieldIndex(BeaconStateFields.BALANCES);
    final long balancesGIndex = schema.getChildGeneralizedIndex(balancesField);
    assertThat(diff.leaves()).hasSizeLessThan(MAX_EPOCH_TRANSITION_LEAVES);
    assertThat(diff.deltas()).extracting(DeltaEntry::fieldIndex).contains(balancesField);
    assertThat(diff.leaves()).noneMatch(entry -> isWithin(entry.gIndex(), balancesGIndex));
    assertThat(diff.supernodes())
        .noneMatch(entry -> entry.fieldIndex() == balancesField)
        .noneMatch(entry -> isWithin(entry.gIndex(), balancesGIndex));
    assertThat(diff.zeroed()).noneMatch(entry -> isWithin(entry.gIndex(), balancesGIndex));
    assertThat(diff.copied()).noneMatch(entry -> isWithin(entry.targetGIndex(), balancesGIndex));

    final TreeNode reconstructed =
        support
            .applierFor(base.getSlot(), supernodeSource)
            .apply(base.getBackingNode(), roundTripCodec(diff));
    assertThat(reconstructed.hashTreeRoot()).isEqualTo(target.hashTreeRoot());
    assertSameState(reconstructed, target, "Epoch transition");
  }

  private TreeDiff diff(final BeaconState base, final BeaconState target) {
    return support
        .differFor(base.getSlot())
        .diff(base.getBackingNode(), target.getBackingNode(), supernodes::put);
  }

  /**
   * Encodes and decodes {@code diff}. The codec sorts entries by gIndex, so the decoded diff is
   * compared through its encoding rather than entry order.
   */
  private static TreeDiff roundTripCodec(final TreeDiff diff) {
    final Bytes encoded = TreeDiffCodec.serialize(diff);
    final TreeDiff decoded = TreeDiffCodec.deserialize(encoded);
    assertThat(decoded.entryCount()).isEqualTo(diff.entryCount());
    assertThat(TreeDiffCodec.serialize(decoded)).isEqualTo(encoded);
    return decoded;
  }

  private void assertSameState(
      final TreeNode reconstructed, final BeaconState expected, final String description) {
    final BeaconState state =
        support.schemaFor(expected.getSlot()).createFromBackingNode(reconstructed);
    assertThat(state.sszSerialize())
        .as("%s: SSZ bytes", description)
        .isEqualTo(expected.sszSerialize());
  }

  private static boolean isWithin(final long gIndex, final long ancestorGIndex) {
    final NodeRelation relation = GIndexUtil.gIdxCompare(gIndex, ancestorGIndex);
    return relation == NodeRelation.SAME || relation == NodeRelation.SUCCESSOR;
  }

  private BeaconState applyRandomMutations(final BeaconState state, final Random rng) {
    final int numMutations = rng.nextInt(MAX_MUTATIONS_PER_TRIAL) + 1;
    BeaconState mutated = state;
    for (int m = 0; m < numMutations; m++) {
      mutated = applyRandomMutation(mutated, rng);
    }
    return mutated;
  }

  private BeaconState applyRandomMutation(final BeaconState state, final Random rng) {
    final int mutationType = rng.nextInt(12);
    return switch (mutationType) {
      case 0 -> mutateSlot(state, rng);
      case 1 -> mutateBlockRoots(state, rng);
      case 2 -> mutateStateRoots(state, rng);
      case 3 -> mutateRandaoMixes(state, rng);
      case 4 -> mutateBalances(state, rng);
      case 5 -> mutateValidatorFields(state, rng);
      case 6 -> appendValidators(state, rng);
      case 7 -> mutateInactivityScores(state, rng);
      case 8 -> mutateParticipation(state, rng);
      case 9 -> mutateSlashings(state, rng);
      case 10 -> mutateCheckpoints(state, rng);
      case 11 -> mutateEth1Data(state);
      default -> state;
    };
  }

  private BeaconState mutateSlot(final BeaconState state, final Random rng) {
    return state.updated(s -> s.setSlot(s.getSlot().plus(rng.nextInt(32) + 1)));
  }

  private BeaconState mutateBlockRoots(final BeaconState state, final Random rng) {
    return state.updated(
        s -> {
          final int size = s.getBlockRoots().size();
          final int count = rng.nextInt(5) + 1;
          for (int i = 0; i < count; i++) {
            s.getBlockRoots().setElement(rng.nextInt(size), dataStructureUtil.randomBytes32());
          }
        });
  }

  private BeaconState mutateStateRoots(final BeaconState state, final Random rng) {
    return state.updated(
        s -> {
          final int size = s.getStateRoots().size();
          final int count = rng.nextInt(5) + 1;
          for (int i = 0; i < count; i++) {
            s.getStateRoots().setElement(rng.nextInt(size), dataStructureUtil.randomBytes32());
          }
        });
  }

  private BeaconState mutateRandaoMixes(final BeaconState state, final Random rng) {
    return state.updated(
        s -> {
          final int size = s.getRandaoMixes().size();
          final int count = rng.nextInt(5) + 1;
          for (int i = 0; i < count; i++) {
            s.getRandaoMixes().setElement(rng.nextInt(size), dataStructureUtil.randomBytes32());
          }
        });
  }

  private BeaconState mutateBalances(final BeaconState state, final Random rng) {
    return state.updated(
        s -> {
          final int size = s.getBalances().size();
          if (size == 0) {
            return;
          }
          final int count = Math.min(rng.nextInt(20) + 1, size);
          for (int i = 0; i < count; i++) {
            final int idx = rng.nextInt(size);
            final long current = s.getBalances().getElement(idx).longValue();
            final long delta = rng.nextInt(2001) - 1000L;
            s.getBalances().setElement(idx, UInt64.valueOf(Math.max(0, current + delta)));
          }
        });
  }

  private BeaconState mutateValidatorFields(final BeaconState state, final Random rng) {
    return state.updated(
        s -> {
          final int size = s.getValidators().size();
          if (size == 0) {
            return;
          }
          final int count = Math.min(rng.nextInt(5) + 1, size);
          for (int i = 0; i < count; i++) {
            final int idx = rng.nextInt(size);
            final Validator validator = s.getValidators().get(idx);
            final Validator updated =
                switch (rng.nextInt(3)) {
                  case 0 ->
                      validator.withEffectiveBalance(
                          UInt64.valueOf(rng.nextInt(32_000_000) + 1_000_000));
                  case 1 -> validator.withSlashed(!validator.isSlashed());
                  default -> validator.withExitEpoch(UInt64.valueOf(rng.nextInt(100000)));
                };
            s.getValidators().set(idx, updated);
          }
        });
  }

  private BeaconState appendValidators(final BeaconState state, final Random rng) {
    return state.updated(
        s -> {
          final MutableBeaconStateAltair altairState = MutableBeaconStateAltair.required(s);
          final int count = rng.nextInt(3) + 1;
          for (int i = 0; i < count; i++) {
            s.getValidators().append(dataStructureUtil.randomValidator());
            s.getBalances().appendElement(dataStructureUtil.randomUInt64());
            altairState.getPreviousEpochParticipation().append(SszByte.of(rng.nextInt(8)));
            altairState.getCurrentEpochParticipation().append(SszByte.of(rng.nextInt(8)));
            altairState.getInactivityScores().appendElement(UInt64.valueOf(rng.nextInt(100)));
          }
        });
  }

  private BeaconState mutateInactivityScores(final BeaconState state, final Random rng) {
    return state.updated(
        s -> {
          final MutableBeaconStateAltair altairState = MutableBeaconStateAltair.required(s);
          final int size = altairState.getInactivityScores().size();
          if (size == 0) {
            return;
          }
          final int count = Math.min(rng.nextInt(10) + 1, size);
          for (int i = 0; i < count; i++) {
            final int idx = rng.nextInt(size);
            final long current = altairState.getInactivityScores().getElement(idx).longValue();
            final long delta = rng.nextInt(201) - 100L;
            altairState
                .getInactivityScores()
                .setElement(idx, UInt64.valueOf(Math.max(0, current + delta)));
          }
        });
  }

  private BeaconState mutateParticipation(final BeaconState state, final Random rng) {
    return state.updated(
        s -> {
          final MutableBeaconStateAltair altairState = MutableBeaconStateAltair.required(s);
          final int size = altairState.getPreviousEpochParticipation().size();
          if (size == 0) {
            return;
          }
          final int count = Math.min(rng.nextInt(10) + 1, size);
          for (int i = 0; i < count; i++) {
            final int idx = rng.nextInt(size);
            altairState.getPreviousEpochParticipation().set(idx, SszByte.of(rng.nextInt(8)));
            altairState.getCurrentEpochParticipation().set(idx, SszByte.of(rng.nextInt(8)));
          }
        });
  }

  private BeaconState mutateSlashings(final BeaconState state, final Random rng) {
    return state.updated(
        s -> {
          final int size = s.getSlashings().size();
          final int count = rng.nextInt(3) + 1;
          for (int i = 0; i < count; i++) {
            s.getSlashings().setElement(rng.nextInt(size), dataStructureUtil.randomUInt64());
          }
        });
  }

  private BeaconState mutateCheckpoints(final BeaconState state, final Random rng) {
    return state.updated(
        s -> {
          switch (rng.nextInt(3)) {
            case 0 -> s.setFinalizedCheckpoint(dataStructureUtil.randomCheckpoint());
            case 1 -> s.setCurrentJustifiedCheckpoint(dataStructureUtil.randomCheckpoint());
            default -> s.setPreviousJustifiedCheckpoint(dataStructureUtil.randomCheckpoint());
          }
        });
  }

  private BeaconState mutateEth1Data(final BeaconState state) {
    return state.updated(s -> s.setEth1Data(dataStructureUtil.randomEth1Data()));
  }
}
