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

import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

class SimpleSszDiffTest {

  private final SimpleSszDiff.Schema schema = new SimpleSszDiff.Schema();

  @Test
  void identicalStates_producesEmptyDiff() {
    final Bytes state = Bytes.random(1024);
    final StateDiff diff = schema.computeDiff(state, state);
    final Bytes result = diff.apply(state);
    assertThat(result).isEqualTo(state);
  }

  @Test
  void identicalStates_roundTripSerialization() {
    final Bytes state = Bytes.random(1024);
    final StateDiff diff = schema.computeDiff(state, state);
    final Bytes serialized = diff.serialize();
    final StateDiff deserialized = schema.deserialize(serialized);
    assertThat(deserialized.apply(state)).isEqualTo(state);
  }

  @Test
  void singleChunkChange_reconstructsCorrectly() {
    final byte[] baseArr = new byte[256];
    final byte[] targetArr = new byte[256];
    System.arraycopy(baseArr, 0, targetArr, 0, 256);
    // Change one 32-byte chunk
    targetArr[64] = (byte) 0xFF;
    targetArr[65] = (byte) 0xAB;

    final Bytes base = Bytes.wrap(baseArr);
    final Bytes target = Bytes.wrap(targetArr);
    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);
  }

  @Test
  void targetLongerThanBase_appendsCorrectly() {
    final Bytes base = Bytes.random(128);
    final Bytes extension = Bytes.random(64);
    final Bytes target = Bytes.concatenate(base, extension);

    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);
  }

  @Test
  void targetShorterThanBase_truncatesCorrectly() {
    final Bytes base = Bytes.random(256);
    final Bytes target = base.slice(0, 128);

    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);
  }

  @Test
  void emptyBase_createsFullPatch() {
    final Bytes target = Bytes.random(128);
    final StateDiff diff = schema.computeDiff(Bytes.EMPTY, target);
    assertThat(diff.apply(Bytes.EMPTY)).isEqualTo(target);
  }

  @Test
  void emptyTarget_reconstructsEmpty() {
    final Bytes base = Bytes.random(128);
    final StateDiff diff = schema.computeDiff(base, Bytes.EMPTY);
    assertThat(diff.apply(base)).isEqualTo(Bytes.EMPTY);
  }

  @Test
  void randomStates_roundTripProperty() {
    final Random rng = new Random(42);
    for (int trial = 0; trial < 50; trial++) {
      final int baseSize = rng.nextInt(2048) + 32;
      final int targetSize = rng.nextInt(2048) + 32;
      final byte[] baseArr = new byte[baseSize];
      final byte[] targetArr = new byte[targetSize];
      rng.nextBytes(baseArr);
      // Start from base and modify random positions
      System.arraycopy(baseArr, 0, targetArr, 0, Math.min(baseSize, targetSize));
      // Modify ~10% of bytes
      for (int i = 0; i < targetSize / 10; i++) {
        targetArr[rng.nextInt(targetSize)] = (byte) rng.nextInt(256);
      }

      final Bytes base = Bytes.wrap(baseArr);
      final Bytes target = Bytes.wrap(targetArr);

      final StateDiff diff = schema.computeDiff(base, target);
      final Bytes serialized = diff.serialize();
      final StateDiff deserialized = schema.deserialize(serialized);
      assertThat(deserialized.apply(base)).isEqualTo(target);
    }
  }

  @Test
  void multipleDisjointChanges_allApplied() {
    final byte[] baseArr = new byte[320];
    final byte[] targetArr = new byte[320];
    System.arraycopy(baseArr, 0, targetArr, 0, 320);
    // Change chunks at different positions
    targetArr[0] = 1;
    targetArr[128] = 2;
    targetArr[256] = 3;

    final Bytes base = Bytes.wrap(baseArr);
    final Bytes target = Bytes.wrap(targetArr);
    final StateDiff diff = schema.computeDiff(base, target);
    assertThat(diff.apply(base)).isEqualTo(target);
  }

  @Test
  void nonAlignedSize_handledCorrectly() {
    // Size not a multiple of 32
    final Bytes base = Bytes.random(100);
    final byte[] targetArr = base.toArray();
    targetArr[99] = (byte) (targetArr[99] ^ 0xFF);
    final Bytes target = Bytes.wrap(targetArr);

    final StateDiff diff = schema.computeDiff(base, target);
    final Bytes serialized = diff.serialize();
    final StateDiff deserialized = schema.deserialize(serialized);
    assertThat(deserialized.apply(base)).isEqualTo(target);
  }
}
