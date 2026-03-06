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

class UInt64DeltaDiffTest {

  private final UInt64DeltaDiff.Schema schema = new UInt64DeltaDiff.Schema();

  private static Bytes uint64ListToSsz(final long... values) {
    final ByteBuffer buf = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
    for (final long v : values) {
      buf.putLong(v);
    }
    return Bytes.wrap(buf.array());
  }

  @Test
  void identicalLists_producesZeroDeltas() {
    final Bytes list = uint64ListToSsz(100, 200, 300);
    final StateDiff diff = schema.computeDiff(list, list);
    assertThat(diff.apply(list)).isEqualTo(list);
  }

  @Test
  void smallDeltas_compressesWell() {
    final Bytes base = uint64ListToSsz(1000, 2000, 3000);
    final Bytes target = uint64ListToSsz(1001, 1999, 3000);
    final StateDiff diff = schema.computeDiff(base, target);

    assertThat(diff.apply(base)).isEqualTo(target);
    // Serialized diff should be smaller than the raw data
    assertThat(diff.serialize().size()).isLessThan(target.size());
  }

  @Test
  void negativeDeltas_handledCorrectly() {
    final Bytes base = uint64ListToSsz(1000, 2000, 3000);
    final Bytes target = uint64ListToSsz(500, 1000, 1500);
    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);
  }

  @Test
  void listGrowth_appendsNewValidators() {
    final Bytes base = uint64ListToSsz(100, 200);
    final Bytes target = uint64ListToSsz(100, 200, 300, 400);
    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);
  }

  @Test
  void listShrink_truncatesCorrectly() {
    final Bytes base = uint64ListToSsz(100, 200, 300, 400);
    final Bytes target = uint64ListToSsz(100, 200);
    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);
  }

  @Test
  void emptyLists_handledCorrectly() {
    final Bytes empty = Bytes.EMPTY;
    final Bytes list = uint64ListToSsz(100, 200);

    assertThat(schema.computeDiff(empty, empty).apply(empty)).isEqualTo(empty);
    assertThat(schema.computeDiff(empty, list).apply(empty)).isEqualTo(list);
  }

  @Test
  void roundTripSerialization() {
    final Bytes base = uint64ListToSsz(1000, 2000, 3000);
    final Bytes target = uint64ListToSsz(1001, 1999, 3000, 4000);

    final StateDiff diff = schema.computeDiff(base, target);
    final Bytes serialized = diff.serialize();
    final StateDiff deserialized = schema.deserialize(serialized);
    assertThat(deserialized.apply(base)).isEqualTo(target);
  }

  @Test
  void largeDeltas_handledCorrectly() {
    final Bytes base = uint64ListToSsz(0, Long.MAX_VALUE);
    final Bytes target = uint64ListToSsz(Long.MAX_VALUE, 0);
    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);
  }

  @Test
  void zigzagEncoding_correctForKnownValues() {
    assertThat(UInt64DeltaDiff.zigzagEncode(0)).isEqualTo(0);
    assertThat(UInt64DeltaDiff.zigzagEncode(-1)).isEqualTo(1);
    assertThat(UInt64DeltaDiff.zigzagEncode(1)).isEqualTo(2);
    assertThat(UInt64DeltaDiff.zigzagEncode(-2)).isEqualTo(3);

    // Round-trip
    for (long v = -1000; v <= 1000; v++) {
      assertThat(UInt64DeltaDiff.zigzagDecode(UInt64DeltaDiff.zigzagEncode(v))).isEqualTo(v);
    }
  }

  @Test
  void randomLists_roundTripProperty() {
    final Random rng = new Random(42);
    for (int trial = 0; trial < 50; trial++) {
      final int baseLen = rng.nextInt(100) + 1;
      final int targetLen = rng.nextInt(100) + 1;

      final long[] baseVals = new long[baseLen];
      final long[] targetVals = new long[targetLen];
      for (int i = 0; i < baseLen; i++) {
        baseVals[i] = rng.nextLong() & Long.MAX_VALUE;
      }
      // Start from base values and apply small changes
      final int commonLen = Math.min(baseLen, targetLen);
      for (int i = 0; i < commonLen; i++) {
        targetVals[i] = baseVals[i] + (rng.nextInt(201) - 100);
      }
      for (int i = commonLen; i < targetLen; i++) {
        targetVals[i] = rng.nextLong() & Long.MAX_VALUE;
      }

      final Bytes base = uint64ListToSsz(baseVals);
      final Bytes target = uint64ListToSsz(targetVals);

      final StateDiff diff = schema.computeDiff(base, target);
      final Bytes serialized = diff.serialize();
      final StateDiff deserialized = schema.deserialize(serialized);
      assertThat(deserialized.apply(base)).isEqualTo(target);
    }
  }
}
