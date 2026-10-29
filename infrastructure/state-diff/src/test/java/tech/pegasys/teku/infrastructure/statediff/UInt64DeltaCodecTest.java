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

package tech.pegasys.teku.infrastructure.statediff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

class UInt64DeltaCodecTest {

  @Test
  void roundTripsIdenticalArrays() {
    final long[] values = {32_000_000_000L, 31_999_999_999L, 0L, 1L, Long.MAX_VALUE};
    final byte[] encoded = UInt64DeltaCodec.encode(values, values.clone());

    assertThat(encoded.length).isLessThanOrEqualTo(2 + values.length);
    assertThat(UInt64DeltaCodec.apply(values, encoded)).containsExactly(values);
  }

  @Test
  void roundTripsGrowth() {
    final long[] base = {10L, 20L, 30L};
    final long[] target = {11L, 19L, 30L, 32_000_000_000L, 7L};

    assertRoundTrip(base, target);
  }

  @Test
  void roundTripsShrink() {
    final long[] base = {10L, 20L, 30L, 40L, 50L};
    final long[] target = {9L, 21L, 30L};

    assertRoundTrip(base, target);
  }

  @Test
  void roundTripsNegativeAndLargeDeltas() {
    final long[] base = {0L, Long.MAX_VALUE, -1L, Long.MIN_VALUE, Long.MAX_VALUE - 5};
    final long[] target = {Long.MAX_VALUE, 0L, 0L, Long.MAX_VALUE, Long.MIN_VALUE + 5};

    assertRoundTrip(base, target);
  }

  @Test
  void roundTripsEmptyToEmpty() {
    final long[] empty = new long[0];
    final byte[] encoded = UInt64DeltaCodec.encode(empty, empty);

    assertThat(encoded).hasSize(2);
    assertThat(UInt64DeltaCodec.apply(empty, encoded)).isEmpty();
  }

  @Test
  void rejectsBaseCountMismatch() {
    final byte[] encoded = UInt64DeltaCodec.encode(new long[] {1L, 2L, 3L}, new long[] {1L, 2L});

    assertThatThrownBy(() -> UInt64DeltaCodec.apply(new long[] {1L, 2L}, encoded))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOversizedTargetCount() {
    // baseCount = 0, targetCount = 2^31 - 1 followed by a single byte
    final byte[] encoded = {0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x07, 0x01};

    assertThatThrownBy(() -> UInt64DeltaCodec.apply(new long[0], encoded))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void randomRoundTrip() {
    final Random rng = new Random(42);
    for (int iteration = 0; iteration < 200; iteration++) {
      final long[] base = randomArray(rng, rng.nextInt(1001));
      final long[] target = randomArray(rng, rng.nextInt(1001));

      assertRoundTrip(base, target);
    }
  }

  @Test
  void appliesInPlaceAsTheAllocatingApply() {
    final Random rng = new Random(43);
    for (int iteration = 0; iteration < 200; iteration++) {
      final long[] base = randomArray(rng, rng.nextInt(101));
      // Same length half of the time, else growing or shrinking
      final long[] target = randomArray(rng, rng.nextBoolean() ? base.length : rng.nextInt(101));
      final byte[] encoded = UInt64DeltaCodec.encode(base, target);
      final long[] expected = UInt64DeltaCodec.apply(base.clone(), encoded);

      final long[] values = base.clone();
      final long[] result = UInt64DeltaCodec.applyInPlace(values, encoded);

      assertThat(result).containsExactly(expected).containsExactly(target);
      if (target.length == base.length) {
        assertThat(result).isSameAs(values);
      }
    }
  }

  @Test
  void applyLeavesItsBaseUnchanged() {
    final long[] base = {10L, 20L, 30L};
    final byte[] encoded = UInt64DeltaCodec.encode(base, new long[] {11L, 19L, 31L});

    UInt64DeltaCodec.apply(base, encoded);

    assertThat(base).containsExactly(10L, 20L, 30L);
  }

  private static long[] randomArray(final Random rng, final int length) {
    final long[] values = new long[length];
    for (int i = 0; i < length; i++) {
      values[i] = rng.nextLong();
    }
    return values;
  }

  private static void assertRoundTrip(final long[] base, final long[] target) {
    final byte[] encoded = UInt64DeltaCodec.encode(base, target);
    assertThat(UInt64DeltaCodec.apply(base, encoded)).containsExactly(target);
  }
}
