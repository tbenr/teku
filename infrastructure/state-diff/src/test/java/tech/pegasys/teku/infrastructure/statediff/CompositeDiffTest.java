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

package tech.pegasys.teku.infrastructure.statediff;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

class CompositeDiffTest {

  /**
   * Creates a simple SSZ-like container with a fixed header, a uint64 list field, and a trailing
   * fixed field. Layout: [4B offset_to_uint64_list][32B fixed_data][uint64_list...]
   */
  private static Bytes buildMockState(final byte[] fixedData, final long... balances) {
    final int headerSize = 4; // one offset for the variable field
    final int fixedFieldSize = fixedData.length;
    final int variableOffset = headerSize + fixedFieldSize;
    final int totalSize = variableOffset + balances.length * 8;

    final ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(variableOffset); // offset to balances
    buf.put(fixedData);
    for (final long b : balances) {
      buf.putLong(b);
    }
    return Bytes.wrap(buf.array());
  }

  @Test
  void identicalStates_roundTrips() {
    final byte[] fixed = new byte[32];
    fixed[0] = 1;
    final Bytes state = buildMockState(fixed, 1000, 2000, 3000);

    // Field locator: offset at position 0, the uint64 field is variable field index 0
    final SszFieldLocator locator =
        new SszFieldLocator.IndexBasedFieldLocator(new int[] {0}, new int[] {0});
    final CompositeDiffSchema schema = new CompositeDiffSchema(locator);

    final StateDiff diff = schema.computeDiff(state, state);
    assertThat(diff.apply(state)).isEqualTo(state);
  }

  @Test
  void balanceChanges_useDeltaEncoding() {
    final byte[] fixed = new byte[32];
    final Bytes base = buildMockState(fixed, 1000, 2000, 3000);
    final Bytes target = buildMockState(fixed, 1001, 1999, 3005);

    final SszFieldLocator locator =
        new SszFieldLocator.IndexBasedFieldLocator(new int[] {0}, new int[] {0});
    final CompositeDiffSchema schema = new CompositeDiffSchema(locator);

    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);
  }

  @Test
  void fixedFieldChanges_useBinaryDiff() {
    final byte[] fixedBase = new byte[32];
    final byte[] fixedTarget = new byte[32];
    fixedTarget[10] = (byte) 0xFF;

    final Bytes base = buildMockState(fixedBase, 1000, 2000);
    final Bytes target = buildMockState(fixedTarget, 1000, 2000);

    final SszFieldLocator locator =
        new SszFieldLocator.IndexBasedFieldLocator(new int[] {0}, new int[] {0});
    final CompositeDiffSchema schema = new CompositeDiffSchema(locator);

    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);
  }

  @Test
  void bothFixedAndBalanceChanges() {
    final byte[] fixedBase = new byte[32];
    final byte[] fixedTarget = new byte[32];
    fixedTarget[5] = 42;

    final Bytes base = buildMockState(fixedBase, 1000, 2000, 3000);
    final Bytes target = buildMockState(fixedTarget, 1001, 2002, 3003);

    final SszFieldLocator locator =
        new SszFieldLocator.IndexBasedFieldLocator(new int[] {0}, new int[] {0});
    final CompositeDiffSchema schema = new CompositeDiffSchema(locator);

    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);
  }

  @Test
  void listGrowth_handledByComposite() {
    final byte[] fixed = new byte[32];
    final Bytes base = buildMockState(fixed, 1000, 2000);
    final Bytes target = buildMockState(fixed, 1000, 2000, 3000, 4000);

    final SszFieldLocator locator =
        new SszFieldLocator.IndexBasedFieldLocator(new int[] {0}, new int[] {0});
    final CompositeDiffSchema schema = new CompositeDiffSchema(locator);

    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);
  }

  @Test
  void roundTripSerialization() {
    final byte[] fixedBase = new byte[32];
    final byte[] fixedTarget = new byte[32];
    fixedTarget[0] = 99;

    final Bytes base = buildMockState(fixedBase, 1000, 2000, 3000);
    final Bytes target = buildMockState(fixedTarget, 1001, 1999, 3000, 4000);

    final SszFieldLocator locator =
        new SszFieldLocator.IndexBasedFieldLocator(new int[] {0}, new int[] {0});
    final CompositeDiffSchema schema = new CompositeDiffSchema(locator);

    final StateDiff diff = schema.computeDiff(base, target);
    final Bytes serialized = diff.serialize();
    final StateDiff deserialized = schema.deserialize(serialized);
    assertThat(deserialized.apply(base)).isEqualTo(target);
  }

  @Test
  void compressedComposite_roundTrips() {
    final byte[] fixedBase = new byte[32];
    final byte[] fixedTarget = new byte[32];
    fixedTarget[0] = 99;

    final Bytes base = buildMockState(fixedBase, 1000, 2000, 3000);
    final Bytes target = buildMockState(fixedTarget, 1001, 1999, 3000, 4000);

    final SszFieldLocator locator =
        new SszFieldLocator.IndexBasedFieldLocator(new int[] {0}, new int[] {0});
    final CompressedDiffSchema schema = new CompressedDiffSchema(new CompositeDiffSchema(locator));

    final StateDiff diff = schema.computeDiff(base, target);
    final Bytes serialized = diff.serialize();
    final StateDiff deserialized = schema.deserialize(serialized);
    assertThat(deserialized.apply(base)).isEqualTo(target);
  }

  /**
   * Creates a mock state with two variable fields: a preceding variable-length field (e.g.
   * validators) and a uint64 list (e.g. balances). When the preceding field grows, the uint64
   * region shifts to a different offset. Layout: [4B offset_a][4B offset_b][fixed][var_a][var_b
   * uint64s]
   */
  private static Bytes buildMockStateTwoVarFields(
      final byte[] fixedData, final byte[] precedingVarData, final long... balances) {
    final int headerSize = 4 + 4; // two offsets
    final int fixedSize = fixedData.length;
    final int offsetA = headerSize + fixedSize;
    final int offsetB = offsetA + precedingVarData.length;
    final int totalSize = offsetB + balances.length * 8;

    final ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(offsetA);
    buf.putInt(offsetB);
    buf.put(fixedData);
    buf.put(precedingVarData);
    for (final long b : balances) {
      buf.putLong(b);
    }
    return Bytes.wrap(buf.array());
  }

  @Test
  void offsetShiftFromGrowingPrecedingField_handledByPerFieldDiff() {
    final byte[] fixed = new byte[32];
    final byte[] varSmall = new byte[100];
    final byte[] varLarge = new byte[200];
    new Random(99).nextBytes(varSmall);
    new Random(77).nextBytes(varLarge);

    final Bytes base = buildMockStateTwoVarFields(fixed, varSmall, 1000, 2000, 3000);
    final Bytes target = buildMockStateTwoVarFields(fixed, varLarge, 1001, 2002, 3003);

    // uint64 field is variable field index 1 (offsets at positions 0 and 4)
    final SszFieldLocator locator =
        new SszFieldLocator.IndexBasedFieldLocator(new int[] {0, 4}, new int[] {1});
    final CompositeDiffSchema schema = new CompositeDiffSchema(locator);

    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);

    // Round-trip serialization
    final Bytes serialized = diff.serialize();
    final StateDiff deserialized = schema.deserialize(serialized);
    assertThat(deserialized.apply(base)).isEqualTo(target);
  }

  @Test
  void offsetShiftWithCompression_roundTrips() {
    final byte[] fixed = new byte[32];
    final byte[] varSmall = new byte[100];
    final byte[] varLarge = new byte[200];
    new Random(99).nextBytes(varSmall);
    new Random(77).nextBytes(varLarge);

    final Bytes base = buildMockStateTwoVarFields(fixed, varSmall, 1000, 2000, 3000);
    final Bytes target = buildMockStateTwoVarFields(fixed, varLarge, 1001, 2002, 3003, 4004);

    final SszFieldLocator locator =
        new SszFieldLocator.IndexBasedFieldLocator(new int[] {0, 4}, new int[] {1});
    final CompressedDiffSchema schema = new CompressedDiffSchema(new CompositeDiffSchema(locator));

    final StateDiff diff = schema.computeDiff(base, target);
    final Bytes serialized = diff.serialize();
    final StateDiff deserialized = schema.deserialize(serialized);
    assertThat(deserialized.apply(base)).isEqualTo(target);
  }

  @Test
  void randomStates_roundTripProperty() {
    final Random rng = new Random(42);
    final SszFieldLocator locator =
        new SszFieldLocator.IndexBasedFieldLocator(new int[] {0}, new int[] {0});
    final CompositeDiffSchema schema = new CompositeDiffSchema(locator);

    for (int trial = 0; trial < 20; trial++) {
      final byte[] fixedBase = new byte[32];
      final byte[] fixedTarget = new byte[32];
      rng.nextBytes(fixedBase);
      System.arraycopy(fixedBase, 0, fixedTarget, 0, 32);
      fixedTarget[rng.nextInt(32)] = (byte) rng.nextInt(256);

      final int baseBalances = rng.nextInt(50) + 1;
      final int targetBalances = rng.nextInt(50) + 1;
      final long[] baseVals = new long[baseBalances];
      final long[] targetVals = new long[targetBalances];
      for (int i = 0; i < baseBalances; i++) {
        baseVals[i] = 32_000_000_000L + rng.nextInt(1_000_000);
      }
      final int common = Math.min(baseBalances, targetBalances);
      for (int i = 0; i < common; i++) {
        targetVals[i] = baseVals[i] + (rng.nextInt(201) - 100);
      }
      for (int i = common; i < targetBalances; i++) {
        targetVals[i] = 32_000_000_000L + rng.nextInt(1_000_000);
      }

      final Bytes base = buildMockState(fixedBase, baseVals);
      final Bytes target = buildMockState(fixedTarget, targetVals);

      final StateDiff diff = schema.computeDiff(base, target);
      final Bytes serialized = diff.serialize();
      final StateDiff deserialized = schema.deserialize(serialized);
      assertThat(deserialized.apply(base)).as("Trial %d failed", trial).isEqualTo(target);
    }
  }

  // --- Epoch transition simulation tests with 4-field mock beacon state ---

  private static final int VALIDATOR_ENTRY_SIZE = 48;
  // 4 offsets (4B each) + slot(8B) + stateRoot(32B) + other(32B) = 88
  private static final int MOCK_FIXED_PART_SIZE = 4 * 4 + 8 + 32 + 32;

  /**
   * Builds a mock SSZ beacon state with 4 variable fields: validators, balances, participation,
   * inactivity_scores. Layout: [4B offset_validators][4B offset_balances][4B
   * offset_participation][4B offset_inactivity] [slot(8B) + stateRoot(32B) + padding(32B)]
   * [validators: N × 48B] [balances: N × 8B uint64] [participation: N × 1B] [inactivity_scores: N ×
   * 8B uint64]
   */
  private static Bytes buildMockBeaconState(
      final long slot,
      final byte[] stateRoot,
      final int validatorCount,
      final long[] balances,
      final byte[] participation,
      final long[] inactivityScores) {
    final int validatorsSize = validatorCount * VALIDATOR_ENTRY_SIZE;
    final int balancesSize = validatorCount * 8;
    final int participationSize = validatorCount;
    final int inactivitySize = validatorCount * 8;
    final int totalSize =
        MOCK_FIXED_PART_SIZE + validatorsSize + balancesSize + participationSize + inactivitySize;

    final int offsetValidators = MOCK_FIXED_PART_SIZE;
    final int offsetBalances = offsetValidators + validatorsSize;
    final int offsetParticipation = offsetBalances + balancesSize;
    final int offsetInactivity = offsetParticipation + participationSize;

    final ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
    // Offsets
    buf.putInt(offsetValidators);
    buf.putInt(offsetBalances);
    buf.putInt(offsetParticipation);
    buf.putInt(offsetInactivity);
    // Fixed fields
    buf.putLong(slot);
    buf.put(stateRoot);
    buf.put(new byte[32]); // padding

    // Validators: deterministic 48-byte entries based on index
    for (int i = 0; i < validatorCount; i++) {
      final byte[] entry = new byte[VALIDATOR_ENTRY_SIZE];
      entry[0] = (byte) (i & 0xFF);
      entry[1] = (byte) ((i >> 8) & 0xFF);
      buf.put(entry);
    }

    // Balances
    for (int i = 0; i < validatorCount; i++) {
      buf.putLong(balances[i]);
    }

    // Participation
    buf.put(participation, 0, validatorCount);

    // Inactivity scores
    for (int i = 0; i < validatorCount; i++) {
      buf.putLong(inactivityScores[i]);
    }

    return Bytes.wrap(buf.array());
  }

  // 4 variable fields: validators(0), balances(1), participation(2), inactivity(3)
  // uint64 fields are index 1 (balances) and index 3 (inactivity_scores)
  private static final SszFieldLocator MOCK_BEACON_LOCATOR =
      new SszFieldLocator.IndexBasedFieldLocator(new int[] {0, 4, 8, 12}, new int[] {1, 3});

  @Test
  void epochTransition_validatorsGrow_diffStaysSmall() {
    final int baseCount = 1000;
    final int targetCount = 1005;

    final byte[] stateRoot = new byte[32];
    stateRoot[0] = 1;
    final long[] baseBalances = new long[baseCount];
    final long[] targetBalances = new long[targetCount];
    final byte[] baseParticipation = new byte[baseCount];
    final byte[] targetParticipation = new byte[targetCount];
    final long[] baseInactivity = new long[baseCount];
    final long[] targetInactivity = new long[targetCount];

    final Random rng = new Random(123);
    for (int i = 0; i < baseCount; i++) {
      baseBalances[i] = 32_000_000_000L + rng.nextInt(1_000_000);
      targetBalances[i] = baseBalances[i] + (rng.nextInt(201) - 100);
      baseParticipation[i] = (byte) rng.nextInt(8);
      targetParticipation[i] = (byte) rng.nextInt(8); // rewritten each epoch
      baseInactivity[i] = 0;
      targetInactivity[i] = 0;
    }
    // New validators
    for (int i = baseCount; i < targetCount; i++) {
      targetBalances[i] = 32_000_000_000L;
      targetParticipation[i] = (byte) rng.nextInt(8);
      targetInactivity[i] = 0;
    }

    final Bytes base =
        buildMockBeaconState(
            100, stateRoot, baseCount, baseBalances, baseParticipation, baseInactivity);
    stateRoot[0] = 2;
    final Bytes target =
        buildMockBeaconState(
            101, stateRoot, targetCount, targetBalances, targetParticipation, targetInactivity);

    final CompositeDiffSchema schema = new CompositeDiffSchema(MOCK_BEACON_LOCATOR);
    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);

    // Diff should be much smaller than state size (not 40%+ like the alignment bug)
    final Bytes serialized = diff.serialize();
    final int stateSize = target.size();
    assertThat(serialized.size())
        .as("diff size %d should be < 20%% of state size %d", serialized.size(), stateSize)
        .isLessThan(stateSize / 5);
  }

  @Test
  void epochTransition_multipleEpochs_diffStaysSmall() {
    final int baseCount = 1000;
    final int targetCount = 1050; // 16-epoch span, 50 new validators

    final byte[] stateRoot = new byte[32];
    stateRoot[0] = 1;
    final long[] baseBalances = new long[baseCount];
    final long[] targetBalances = new long[targetCount];
    final byte[] baseParticipation = new byte[baseCount];
    final byte[] targetParticipation = new byte[targetCount];
    final long[] baseInactivity = new long[baseCount];
    final long[] targetInactivity = new long[targetCount];

    final Random rng = new Random(456);
    for (int i = 0; i < baseCount; i++) {
      baseBalances[i] = 32_000_000_000L + rng.nextInt(1_000_000);
      targetBalances[i] = baseBalances[i] + (rng.nextInt(10001) - 5000); // larger changes
      baseParticipation[i] = (byte) rng.nextInt(8);
      targetParticipation[i] = (byte) rng.nextInt(8);
      baseInactivity[i] = rng.nextInt(10);
      targetInactivity[i] = baseInactivity[i] + rng.nextInt(3);
    }
    for (int i = baseCount; i < targetCount; i++) {
      targetBalances[i] = 32_000_000_000L;
      targetParticipation[i] = (byte) rng.nextInt(8);
      targetInactivity[i] = 0;
    }

    final Bytes base =
        buildMockBeaconState(
            100, stateRoot, baseCount, baseBalances, baseParticipation, baseInactivity);
    stateRoot[0] = 2;
    final Bytes target =
        buildMockBeaconState(
            116, stateRoot, targetCount, targetBalances, targetParticipation, targetInactivity);

    final CompositeDiffSchema schema = new CompositeDiffSchema(MOCK_BEACON_LOCATOR);
    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);

    final Bytes serialized = diff.serialize();
    final int stateSize = target.size();
    assertThat(serialized.size())
        .as("diff size %d should be < 20%% of state size %d", serialized.size(), stateSize)
        .isLessThan(stateSize / 5);
  }

  @Test
  void epochTransition_noValidatorGrowth_tinyDiff() {
    final int count = 1000;

    final byte[] stateRoot = new byte[32];
    stateRoot[0] = 1;
    final long[] baseBalances = new long[count];
    final long[] targetBalances = new long[count];
    final byte[] baseParticipation = new byte[count];
    final byte[] targetParticipation = new byte[count];
    final long[] inactivity = new long[count]; // same for both

    final Random rng = new Random(789);
    for (int i = 0; i < count; i++) {
      baseBalances[i] = 32_000_000_000L + rng.nextInt(1_000_000);
      // Only a few balances change
      targetBalances[i] = (i < 10) ? baseBalances[i] + 1 : baseBalances[i];
      baseParticipation[i] = (byte) rng.nextInt(8);
      targetParticipation[i] = baseParticipation[i]; // participation unchanged
      inactivity[i] = 0;
    }

    final Bytes base =
        buildMockBeaconState(100, stateRoot, count, baseBalances, baseParticipation, inactivity);
    stateRoot[0] = 2;
    final Bytes target =
        buildMockBeaconState(
            101, stateRoot, count, targetBalances, targetParticipation, inactivity);

    final CompositeDiffSchema schema = new CompositeDiffSchema(MOCK_BEACON_LOCATOR);
    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);

    // Very small diff: only slot, stateRoot, and 10 balance changes
    final Bytes serialized = diff.serialize();
    assertThat(serialized.size())
        .as("diff size %d should be very small", serialized.size())
        .isLessThan(target.size() / 20); // < 5%
  }

  @Test
  void epochTransition_chainOfDiffs_reconstructsCorrectly() {
    final int count0 = 1000;
    final int count1 = 1002;
    final int count2 = 1004;
    final Random rng = new Random(321);

    final byte[] stateRoot0 = new byte[32];
    stateRoot0[0] = 10;
    final long[] bal0 = new long[count0];
    final byte[] part0 = new byte[count0];
    final long[] inact0 = new long[count0];
    for (int i = 0; i < count0; i++) {
      bal0[i] = 32_000_000_000L + rng.nextInt(1_000_000);
      part0[i] = (byte) rng.nextInt(8);
      inact0[i] = 0;
    }
    final Bytes state0 = buildMockBeaconState(100, stateRoot0, count0, bal0, part0, inact0);

    // state_1: +2 validators, participation rewritten, some balance changes
    final byte[] stateRoot1 = new byte[32];
    stateRoot1[0] = 11;
    final long[] bal1 = new long[count1];
    final byte[] part1 = new byte[count1];
    final long[] inact1 = new long[count1];
    for (int i = 0; i < count0; i++) {
      bal1[i] = bal0[i] + (rng.nextInt(201) - 100);
      part1[i] = (byte) rng.nextInt(8);
      inact1[i] = 0;
    }
    for (int i = count0; i < count1; i++) {
      bal1[i] = 32_000_000_000L;
      part1[i] = (byte) rng.nextInt(8);
      inact1[i] = 0;
    }
    final Bytes state1 = buildMockBeaconState(101, stateRoot1, count1, bal1, part1, inact1);

    // state_2: +2 more validators
    final byte[] stateRoot2 = new byte[32];
    stateRoot2[0] = 12;
    final long[] bal2 = new long[count2];
    final byte[] part2 = new byte[count2];
    final long[] inact2 = new long[count2];
    for (int i = 0; i < count1; i++) {
      bal2[i] = bal1[i] + (rng.nextInt(201) - 100);
      part2[i] = (byte) rng.nextInt(8);
      inact2[i] = 0;
    }
    for (int i = count1; i < count2; i++) {
      bal2[i] = 32_000_000_000L;
      part2[i] = (byte) rng.nextInt(8);
      inact2[i] = 0;
    }
    final Bytes state2 = buildMockBeaconState(102, stateRoot2, count2, bal2, part2, inact2);

    final CompositeDiffSchema schema = new CompositeDiffSchema(MOCK_BEACON_LOCATOR);

    final StateDiff diff01 = schema.computeDiff(state0, state1);
    final StateDiff diff12 = schema.computeDiff(state1, state2);

    assertThat(diff01.apply(state0)).isEqualTo(state1);
    assertThat(diff12.apply(state1)).isEqualTo(state2);

    // Round-trip through serialization
    final StateDiff diff01Rt = schema.deserialize(diff01.serialize());
    final StateDiff diff12Rt = schema.deserialize(diff12.serialize());
    final Bytes reconstructed1 = diff01Rt.apply(state0);
    assertThat(reconstructed1).isEqualTo(state1);
    assertThat(diff12Rt.apply(reconstructed1)).isEqualTo(state2);
  }

  @Test
  void epochTransition_withCompression_staysSmall() {
    final int baseCount = 1000;
    final int targetCount = 1005;

    final byte[] stateRoot = new byte[32];
    stateRoot[0] = 1;
    final long[] baseBalances = new long[baseCount];
    final long[] targetBalances = new long[targetCount];
    final byte[] baseParticipation = new byte[baseCount];
    final byte[] targetParticipation = new byte[targetCount];
    final long[] baseInactivity = new long[baseCount];
    final long[] targetInactivity = new long[targetCount];

    final Random rng = new Random(555);
    for (int i = 0; i < baseCount; i++) {
      baseBalances[i] = 32_000_000_000L + rng.nextInt(1_000_000);
      targetBalances[i] = baseBalances[i] + (rng.nextInt(201) - 100);
      baseParticipation[i] = (byte) rng.nextInt(8);
      targetParticipation[i] = (byte) rng.nextInt(8);
      baseInactivity[i] = 0;
      targetInactivity[i] = 0;
    }
    for (int i = baseCount; i < targetCount; i++) {
      targetBalances[i] = 32_000_000_000L;
      targetParticipation[i] = (byte) rng.nextInt(8);
      targetInactivity[i] = 0;
    }

    final Bytes base =
        buildMockBeaconState(
            100, stateRoot, baseCount, baseBalances, baseParticipation, baseInactivity);
    stateRoot[0] = 2;
    final Bytes target =
        buildMockBeaconState(
            101, stateRoot, targetCount, targetBalances, targetParticipation, targetInactivity);

    final CompressedDiffSchema schema =
        new CompressedDiffSchema(new CompositeDiffSchema(MOCK_BEACON_LOCATOR));

    final StateDiff diff = schema.computeDiff(base, target);
    final Bytes serialized = diff.serialize();
    final StateDiff deserialized = schema.deserialize(serialized);
    assertThat(deserialized.apply(base)).isEqualTo(target);

    // Compressed diff should be even smaller
    final int stateSize = target.size();
    assertThat(serialized.size())
        .as(
            "compressed diff size %d should be < 20%% of state size %d",
            serialized.size(), stateSize)
        .isLessThan(stateSize / 5);
  }
}
