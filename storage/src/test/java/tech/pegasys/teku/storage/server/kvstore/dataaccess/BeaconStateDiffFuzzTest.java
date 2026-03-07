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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszByte;
import tech.pegasys.teku.infrastructure.statediff.CompositeDiffSchema;
import tech.pegasys.teku.infrastructure.statediff.CompressedDiffSchema;
import tech.pegasys.teku.infrastructure.statediff.DiffHierarchy;
import tech.pegasys.teku.infrastructure.statediff.SimpleSszDiff;
import tech.pegasys.teku.infrastructure.statediff.StateDiff;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.state.Validator;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.altair.MutableBeaconStateAltair;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.storage.server.kvstore.KvStoreAccessor;
import tech.pegasys.teku.storage.server.kvstore.KvStoreAccessor.KvStoreTransaction;
import tech.pegasys.teku.storage.server.kvstore.MockKvStoreInstance;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.V4FinalizedStateStorageLogic.FinalizedStateUpdater;
import tech.pegasys.teku.storage.server.kvstore.schema.SchemaCombinedDiffState;
import tech.pegasys.teku.storage.server.kvstore.schema.V6SchemaCombinedDiffState;

class BeaconStateDiffFuzzTest {

  private static final int NUM_TRIALS = 50;
  private static final int MAX_MUTATIONS_PER_TRIAL = 20;

  private final Spec spec = TestSpecFactory.createMinimalElectra();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);

  @Test
  void fuzzDiffRoundTrip() {
    final Random rng = new Random(12345);
    final BeaconStateSszFieldLocator fieldLocator = BeaconStateSszFieldLocator.create(spec);

    for (int trial = 0; trial < NUM_TRIALS; trial++) {
      final UInt64 epoch = UInt64.valueOf(rng.nextInt(1000) + 1);
      final BeaconState baseState =
          dataStructureUtil.randomBeaconState(spec.computeStartSlotAtEpoch(epoch));

      final int numMutations = rng.nextInt(MAX_MUTATIONS_PER_TRIAL) + 1;
      BeaconState targetState = baseState;
      for (int m = 0; m < numMutations; m++) {
        targetState = applyRandomMutation(targetState, rng);
      }

      final Bytes baseSsz = baseState.sszSerialize();
      final Bytes targetSsz = targetState.sszSerialize();

      // CompositeDiffSchema
      final CompositeDiffSchema compositeSchema = new CompositeDiffSchema(fieldLocator);
      final StateDiff compositeDiff = compositeSchema.computeDiff(baseSsz, targetSsz);
      assertThat(compositeDiff.apply(baseSsz))
          .as("Trial %d: CompositeDiff apply", trial)
          .isEqualTo(targetSsz);

      // Round-trip through serialization
      final StateDiff deserialized = compositeSchema.deserialize(compositeDiff.serialize());
      final Bytes reconstructed = deserialized.apply(baseSsz);
      assertThat(reconstructed)
          .as("Trial %d: CompositeDiff serialize round-trip", trial)
          .isEqualTo(targetSsz);

      // Verify hashTreeRoot
      final BeaconState reconstructedState = spec.deserializeBeaconState(reconstructed);
      assertThat(reconstructedState.hashTreeRoot())
          .as("Trial %d: hashTreeRoot match", trial)
          .isEqualTo(targetState.hashTreeRoot());

      // CompressedDiffSchema
      final CompressedDiffSchema compressedSchema = new CompressedDiffSchema(compositeSchema);
      final StateDiff compressedDiff = compressedSchema.computeDiff(baseSsz, targetSsz);
      final Bytes compressedReconstructed =
          compressedSchema.deserialize(compressedDiff.serialize()).apply(baseSsz);
      assertThat(compressedReconstructed)
          .as("Trial %d: Compressed round-trip", trial)
          .isEqualTo(targetSsz);

      // SimpleSszDiff
      final SimpleSszDiff.Schema simpleSchema = new SimpleSszDiff.Schema();
      final StateDiff simpleDiff = simpleSchema.computeDiff(baseSsz, targetSsz);
      assertThat(simpleDiff.apply(baseSsz))
          .as("Trial %d: SimpleSszDiff apply", trial)
          .isEqualTo(targetSsz);

      // SimpleSszDiff serialization round-trip
      final StateDiff simpleDeserialized = simpleSchema.deserialize(simpleDiff.serialize());
      assertThat(simpleDeserialized.apply(baseSsz))
          .as("Trial %d: SimpleSszDiff serialize round-trip", trial)
          .isEqualTo(targetSsz);
    }
  }

  @Test
  void fuzzChainThroughStoragePipeline() {
    final Random rng = new Random(67890);
    final int chainLength = 100;
    final V6SchemaCombinedDiffState schema = new V6SchemaCombinedDiffState(spec);
    final KvStoreAccessor db =
        MockKvStoreInstance.createEmpty(schema.getAllColumns(), schema.getAllVariables());
    final V4FinalizedStateDiffStorageLogic logic = V4FinalizedStateDiffStorageLogic.create(spec);

    final List<BeaconState> states = new ArrayList<>();
    BeaconState current =
        dataStructureUtil.randomBeaconState(spec.computeStartSlotAtEpoch(UInt64.valueOf(10)));
    states.add(current);

    for (int i = 1; i < chainLength; i++) {
      final UInt64 nextEpoch = UInt64.valueOf(10 + i);
      final int numMutations = rng.nextInt(MAX_MUTATIONS_PER_TRIAL) + 1;
      for (int m = 0; m < numMutations; m++) {
        current = applyRandomMutation(current, rng);
      }
      // Ensure slot is at the expected epoch boundary after mutations
      final UInt64 expectedSlot = spec.computeStartSlotAtEpoch(nextEpoch);
      current = current.updated(s -> s.setSlot(expectedSlot));
      states.add(current);
    }

    // Store all states through the diff pipeline
    for (final BeaconState state : states) {
      try (final KvStoreTransaction tx = db.startTransaction()) {
        final FinalizedStateUpdater<SchemaCombinedDiffState> updater = logic.updater();
        updater.addFinalizedState(db, tx, schema, state);
        tx.commit();
        updater.commit();
      }
    }

    // Reconstruct and verify every stored state via the reconstruction chain directly.
    // We bypass findLatestStoredEpoch (which searches level-6 first and may miss states
    // stored only at coarser levels) and instead reconstruct each epoch explicitly.
    final BeaconStateSszFieldLocator fieldLocator = BeaconStateSszFieldLocator.create(spec);
    final DiffHierarchy hierarchy =
        DiffHierarchy.createDefault(fieldLocator, BeaconStateSszFieldLocator.getForkEpochs(spec));

    for (int i = 0; i < states.size(); i++) {
      final BeaconState expected = states.get(i);
      final UInt64 targetEpoch = spec.computeEpochAtSlot(expected.getSlot());
      final Bytes reconstructedSsz = reconstructFromChain(db, schema, hierarchy, targetEpoch);
      final BeaconState reconstructed = spec.deserializeBeaconState(reconstructedSsz);
      assertThat(reconstructed.hashTreeRoot())
          .as("Chain state %d (epoch %s): hashTreeRoot", i, targetEpoch)
          .isEqualTo(expected.hashTreeRoot());
      assertThat(reconstructedSsz)
          .as("Chain state %d (epoch %s): SSZ bytes", i, targetEpoch)
          .isEqualTo(expected.sszSerialize());
    }
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
            Validator v = s.getValidators().get(idx);
            switch (rng.nextInt(3)) {
              case 0 ->
                  v = v.withEffectiveBalance(UInt64.valueOf(rng.nextInt(32_000_000) + 1_000_000));
              case 1 -> v = v.withSlashed(!v.isSlashed());
              case 2 -> v = v.withExitEpoch(UInt64.valueOf(rng.nextInt(100000)));
            }
            s.getValidators().set(idx, v);
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
            case 2 -> s.setPreviousJustifiedCheckpoint(dataStructureUtil.randomCheckpoint());
          }
        });
  }

  private BeaconState mutateEth1Data(final BeaconState state) {
    return state.updated(s -> s.setEth1Data(dataStructureUtil.randomEth1Data()));
  }

  private static Bytes reconstructFromChain(
      final KvStoreAccessor db,
      final SchemaCombinedDiffState schema,
      final DiffHierarchy hierarchy,
      final UInt64 targetEpoch) {
    final List<DiffHierarchy.LevelAndEpoch> chain = hierarchy.getReconstructionChain(targetEpoch);
    assertThat(chain).as("Reconstruction chain for epoch %s", targetEpoch).isNotEmpty();

    final DiffHierarchy.LevelAndEpoch snapshotEntry = chain.getFirst();
    final Bytes snapshotBytes =
        db.get(schema.getColumnStateDiffLevel(snapshotEntry.level()), snapshotEntry.epoch())
            .orElseThrow(
                () ->
                    new AssertionError(
                        "Missing snapshot at level "
                            + snapshotEntry.level()
                            + " epoch "
                            + snapshotEntry.epoch()));

    Bytes currentSsz =
        hierarchy.getSchema(snapshotEntry.level()).deserialize(snapshotBytes).apply(Bytes.EMPTY);

    for (int i = 1; i < chain.size(); i++) {
      final DiffHierarchy.LevelAndEpoch entry = chain.get(i);
      final Bytes diffBytes =
          db.get(schema.getColumnStateDiffLevel(entry.level()), entry.epoch())
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "Missing diff at level " + entry.level() + " epoch " + entry.epoch()));
      currentSsz = hierarchy.getSchema(entry.level()).deserialize(diffBytes).apply(currentSsz);
    }
    return currentSsz;
  }
}
