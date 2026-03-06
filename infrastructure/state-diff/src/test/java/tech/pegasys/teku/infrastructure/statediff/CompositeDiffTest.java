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
}
