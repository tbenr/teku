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

import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.CopiedEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.DeltaEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.LeafEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.SupernodeEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.ZeroedEntry;

class TreeDiffCodecTest {

  private static final Bytes32 HASH_A = Bytes32.repeat((byte) 0xaa);
  private static final Bytes32 HASH_B = Bytes32.repeat((byte) 0xbb);

  @Test
  void roundTripsEmptyDiff() {
    final Bytes serialized = TreeDiffCodec.serialize(TreeDiff.empty());

    assertThat(serialized.size()).isEqualTo(6);
    final TreeDiff deserialized = TreeDiffCodec.deserialize(serialized);
    assertThat(deserialized).isEqualTo(TreeDiff.empty());
    assertThat(deserialized.isEmpty()).isTrue();
    assertThat(deserialized.entryCount()).isZero();
  }

  @Test
  void roundTripsAllSections() {
    final Bytes leafData = Bytes.wrap(sequentialBytes(40));
    final TreeDiff diff =
        new TreeDiff(
            List.of(new LeafEntry(5, leafData)),
            List.of(new SupernodeEntry(3, 7, 2, HASH_A)),
            List.of(new ZeroedEntry(13, HASH_B)),
            List.of(new CopiedEntry(13, 5)),
            List.of(new DeltaEntry(12, new byte[] {1, 2, 3})));

    final TreeDiff deserialized = TreeDiffCodec.deserialize(TreeDiffCodec.serialize(diff));

    assertThat(deserialized).isEqualTo(diff);
    assertThat(deserialized.isEmpty()).isFalse();
    assertThat(deserialized.entryCount()).isEqualTo(5);
    assertThat(deserialized.leaves().getFirst().data()).isEqualTo(leafData);
    assertThat(deserialized.deltas().getFirst().encodedDeltas()).containsExactly(1, 2, 3);
  }

  @Test
  void sortsLeavesByGIndex() {
    final TreeDiff diff =
        new TreeDiff(
            List.of(
                new LeafEntry(13, Bytes.of(13)),
                new LeafEntry(5, Bytes.of(5)),
                new LeafEntry(7, Bytes.of(7))),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    final TreeDiff deserialized = TreeDiffCodec.deserialize(TreeDiffCodec.serialize(diff));

    assertThat(deserialized.leaves())
        .containsExactly(
            new LeafEntry(5, Bytes.of(5)),
            new LeafEntry(7, Bytes.of(7)),
            new LeafEntry(13, Bytes.of(13)));
  }

  @Test
  void rejectsUnknownVersion() {
    final MutableBytes serialized = TreeDiffCodec.serialize(TreeDiff.empty()).mutableCopy();
    serialized.set(0, (byte) 2);

    assertThatThrownBy(() -> TreeDiffCodec.deserialize(serialized))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsTruncatedInput() {
    final Bytes serialized = TreeDiffCodec.serialize(sampleDiff());

    assertThatThrownBy(() -> TreeDiffCodec.deserialize(serialized.slice(0, serialized.size() - 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsTrailingBytes() {
    final Bytes serialized = Bytes.concatenate(TreeDiffCodec.serialize(sampleDiff()), Bytes.of(0));

    assertThatThrownBy(() -> TreeDiffCodec.deserialize(serialized))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roundTripsLargeGIndexes() {
    final long large = 1L << 40;
    final long largest = Long.MAX_VALUE >>> 1;
    final TreeDiff diff =
        new TreeDiff(
            List.of(new LeafEntry(large, Bytes.of(1)), new LeafEntry(largest, Bytes.of(2))),
            List.of(
                new SupernodeEntry(4, large, 3, HASH_A), new SupernodeEntry(4, largest, 3, HASH_B)),
            List.of(new ZeroedEntry(large, HASH_A), new ZeroedEntry(largest, HASH_B)),
            List.of(new CopiedEntry(largest, large)),
            List.of());

    assertThat(TreeDiffCodec.deserialize(TreeDiffCodec.serialize(diff))).isEqualTo(diff);
  }

  private static TreeDiff sampleDiff() {
    return new TreeDiff(
        List.of(new LeafEntry(5, Bytes.of(1, 2, 3))),
        List.of(new SupernodeEntry(3, 7, 2, HASH_A)),
        List.of(new ZeroedEntry(13, HASH_B)),
        List.of(new CopiedEntry(13, 5)),
        List.of(new DeltaEntry(12, new byte[] {1, 2, 3})));
  }

  private static byte[] sequentialBytes(final int length) {
    final byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) i;
    }
    return bytes;
  }
}
