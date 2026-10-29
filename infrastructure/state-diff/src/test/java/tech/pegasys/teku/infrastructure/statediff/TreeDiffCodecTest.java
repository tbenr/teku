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

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.CopiedEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.DeltaEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.LeafEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.PatchEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.SupernodeEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.ZeroedEntry;

class TreeDiffCodecTest {

  private static final Bytes32 HASH_A = Bytes32.repeat((byte) 0xaa);
  private static final Bytes32 HASH_B = Bytes32.repeat((byte) 0xbb);

  @Test
  void roundTripsEmptyDiff() {
    final Bytes serialized = TreeDiffCodec.serialize(TreeDiff.empty());

    assertThat(serialized.size()).isEqualTo(7);
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
            List.of(patch(3, 6, 2, 4, 3, IntList.of(1, 3))),
            List.of(new ZeroedEntry(13, HASH_B)),
            List.of(new CopiedEntry(13, 5)),
            List.of(new DeltaEntry(12, new byte[] {1, 2, 3})));

    final TreeDiff deserialized = TreeDiffCodec.deserialize(TreeDiffCodec.serialize(diff));

    assertThat(deserialized).isEqualTo(diff);
    assertThat(deserialized.isEmpty()).isFalse();
    assertThat(deserialized.entryCount()).isEqualTo(6);
    assertThat(deserialized.leaves().getFirst().data()).isEqualTo(leafData);
    assertThat(deserialized.deltas().getFirst().encodedDeltas()).containsExactly(1, 2, 3);
  }

  @Test
  void supernodeHashesMatchesFullDecode() {
    final Bytes32 hashC = Bytes32.repeat((byte) 0xcc);
    final TreeDiff diff =
        new TreeDiff(
            List.of(
                new LeafEntry(5, Bytes.wrap(sequentialBytes(40))), new LeafEntry(9, Bytes.of(1))),
            List.of(
                new SupernodeEntry(4, 40, 2, HASH_B),
                new SupernodeEntry(3, 9, 2, HASH_A),
                new SupernodeEntry(3, 7, 2, hashC),
                new SupernodeEntry(4, 41, 2, HASH_B)),
            List.of(
                patch(4, 42, 2, 4, 5, IntList.of(0, 1, 2, 3)), patch(3, 8, 2, 3, 5, IntList.of(2))),
            List.of(new ZeroedEntry(13, HASH_B), new ZeroedEntry(15, HASH_A)),
            List.of(new CopiedEntry(13, 5)),
            List.of(new DeltaEntry(12, sequentialBytes(70)), new DeltaEntry(14, new byte[0])));
    final Bytes serialized = TreeDiffCodec.serialize(diff);

    assertThat(TreeDiffCodec.supernodeHashes(serialized))
        .isEqualTo(
            TreeDiffCodec.deserialize(serialized).supernodes().stream()
                .map(SupernodeEntry::hash)
                .toList())
        .containsExactly(hashC, HASH_A, HASH_B, HASH_B);
    assertThat(TreeDiffCodec.supernodeEntries(serialized))
        .isEqualTo(TreeDiffCodec.deserialize(serialized).supernodes());
    assertThat(TreeDiffCodec.supernodeHashes(TreeDiffCodec.serialize(TreeDiff.empty()))).isEmpty();
    // Rejects what a full decode rejects, in any section
    for (int length = 0; length < serialized.size(); length++) {
      final Bytes truncated = serialized.slice(0, length);
      assertThatThrownBy(() -> TreeDiffCodec.supernodeHashes(truncated))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> TreeDiffCodec.supernodeHashes(Bytes.concatenate(serialized, Bytes.of(0))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roundTripsPatches() {
    final TreeDiff diff =
        new TreeDiff(
            List.of(),
            List.of(),
            List.of(
                // One rewritten element
                patch(2, 1L << 20, 8, 256, 121, IntList.of(17)),
                // Rewritten and appended elements, written in index order
                patch(2, (1L << 20) + 3, 8, 200, 121, IntList.of(0, 130, 150, 199)),
                // Appended elements left unwritten, zero
                patch(2, (1L << 20) + 1, 8, 12, 121, IntList.of()),
                // Another field with another element size
                patch(7, 50, 3, 8, 32, IntList.of(7))),
            List.of(),
            List.of(),
            List.of());

    final TreeDiff deserialized = TreeDiffCodec.deserialize(TreeDiffCodec.serialize(diff));

    // Sorted by field index, then gIndex
    assertThat(deserialized.patches())
        .containsExactly(
            diff.patches().get(0),
            diff.patches().get(2),
            diff.patches().get(1),
            diff.patches().get(3));
    assertThat(deserialized.entryCount()).isEqualTo(4);
    assertThat(TreeDiffCodec.supernodeEntries(TreeDiffCodec.serialize(diff))).isEmpty();
  }

  @Test
  void rejectsPatchesOfOneFieldWithDifferentElementSizes() {
    final TreeDiff diff =
        new TreeDiff(
            List.of(),
            List.of(),
            List.of(patch(2, 8, 2, 1, 121, IntList.of(0)), patch(2, 9, 2, 1, 120, IntList.of(0))),
            List.of(),
            List.of(),
            List.of());

    assertThatThrownBy(() -> TreeDiffCodec.serialize(diff))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("element size");
  }

  @Test
  void rejectsPatchWritingAnElementBeyondItsCount() {
    final Bytes serialized =
        TreeDiffCodec.serialize(
            new TreeDiff(
                List.of(),
                List.of(),
                List.of(patch(2, 8, 2, 3, 4, IntList.of(2))),
                List.of(),
                List.of(),
                List.of()));
    // version, leaf count, supernode groups, patch groups, field index, element size, entry count,
    // gIndex delta, depth, element count 3
    final int elementCountOffset = 9;
    assertThat(serialized.get(elementCountOffset)).isEqualTo((byte) 3);
    final MutableBytes corrupted = serialized.mutableCopy();
    corrupted.set(elementCountOffset, (byte) 2);

    assertThatThrownBy(() -> TreeDiffCodec.deserialize(corrupted))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("element 2");
  }

  @Test
  void patchEntryRejectsInconsistentContent() {
    assertThatThrownBy(() -> patch(2, 8, 2, 5, 4, IntList.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> patch(2, 8, 2, 4, 4, IntList.of(2, 2)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> patch(2, 8, 2, 4, 0, IntList.of(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> patch(2, 8, 31, 4, 4, IntList.of(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PatchEntry(2, 8, 2, 4, 4, IntList.of(1), Bytes.wrap(sequentialBytes(5))))
        .isInstanceOf(IllegalArgumentException.class);
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
    serialized.set(0, (byte) 3);

    assertThatThrownBy(() -> TreeDiffCodec.deserialize(serialized))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown tree diff version 3");
  }

  @Test
  void writesVersionTwo() {
    assertThat(TreeDiffCodec.serialize(sampleDiff()).get(0)).isEqualTo((byte) 2);
  }

  @Test
  void rejectsVersionOnePayloadWithResyncMessage() {
    // The version 1 encoding of an empty diff: the version byte and five zero section counts
    final Bytes versionOne = Bytes.of(1, 0, 0, 0, 0, 0);

    assertThatThrownBy(() -> TreeDiffCodec.deserialize(versionOne))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TreeDiffCodec.VERSION_ONE_MESSAGE)
        .hasMessageContaining("version 1")
        .hasMessageContaining("re-synced into a fresh data directory");
    assertThatThrownBy(() -> TreeDiffCodec.supernodeEntries(versionOne))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TreeDiffCodec.VERSION_ONE_MESSAGE);
  }

  @Test
  void rejectsTruncatedInputAtEveryOffset() {
    final Bytes serialized =
        TreeDiffCodec.serialize(
            new TreeDiff(
                List.of(new LeafEntry(5, Bytes.of(1, 2, 3))),
                List.of(new SupernodeEntry(3, 7, 2, HASH_A)),
                List.of(
                    patch(3, 6, 2, 4, 3, IntList.of(0, 3)),
                    patch(3, 9, 2, 1, 3, IntList.of()),
                    patch(5, 4, 1, 2, 7, IntList.of(1))),
                List.of(new ZeroedEntry(13, HASH_B)),
                List.of(new CopiedEntry(13, 5)),
                List.of(
                    new DeltaEntry(12, sequentialBytes(300)),
                    new DeltaEntry(14, new byte[0]),
                    new DeltaEntry(21, new byte[] {1, 2, 3}))));

    for (int length = 0; length < serialized.size(); length++) {
      final Bytes truncated = serialized.slice(0, length);
      assertThatThrownBy(() -> TreeDiffCodec.deserialize(truncated))
          .describedAs("truncated to %s of %s bytes", length, serialized.size())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void roundTripsDeltaBlocks() {
    final Random random = new Random(7);
    final long[] randomBase = new long[10_000];
    final long[] randomTarget = new long[10_003];
    for (int i = 0; i < randomBase.length; i++) {
      randomBase[i] = random.nextLong();
    }
    for (int i = 0; i < randomTarget.length; i++) {
      randomTarget[i] = random.nextLong();
    }
    final List<byte[]> blocks =
        List.of(
            new byte[0],
            UInt64DeltaCodec.encode(new long[0], new long[] {42L}),
            UInt64DeltaCodec.encode(new long[2_000_000], new long[2_000_000]),
            UInt64DeltaCodec.encode(randomBase, randomTarget));
    for (final byte[] block : blocks) {
      final TreeDiff diff =
          new TreeDiff(
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(new DeltaEntry(4, block)));

      final TreeDiff deserialized = TreeDiffCodec.deserialize(TreeDiffCodec.serialize(diff));

      assertThat(deserialized).describedAs("block of %s bytes", block.length).isEqualTo(diff);
    }
  }

  @Test
  void compressesZeroDeltaBlock() {
    final byte[] zeros = UInt64DeltaCodec.encode(new long[2_000_000], new long[2_000_000]);
    final TreeDiff diff =
        new TreeDiff(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new DeltaEntry(21, zeros)));

    final TreeDiffCodec.Encoded encoded = TreeDiffCodec.encode(diff);

    assertThat(zeros.length).isGreaterThan(2_000_000);
    assertThat(encoded.compressedDeltaBytes()).isLessThan(1_024);
    assertThat(encoded.payload()).isEqualTo(TreeDiffCodec.serialize(diff));
    assertThat(encoded.payload().size()).isLessThan(1_100);
  }

  @Test
  void rejectsDeltaBlockWhoseRawLengthDoesNotMatch() {
    final TreeDiff diff =
        new TreeDiff(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new DeltaEntry(4, sequentialBytes(100))));
    final Bytes serialized = TreeDiffCodec.serialize(diff);
    // version, leaf count, supernode groups, patch groups, zeroed count, copied count, delta count,
    // field index, then the raw length 100, a one-byte varint
    final int rawLengthOffset = 8;
    assertThat(serialized.get(rawLengthOffset)).isEqualTo((byte) 100);
    final MutableBytes corrupted = serialized.mutableCopy();
    corrupted.set(rawLengthOffset, (byte) 101);

    assertThatThrownBy(() -> TreeDiffCodec.deserialize(corrupted))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("101");
    assertThatThrownBy(() -> TreeDiffCodec.supernodeEntries(corrupted))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("101");
  }

  @Test
  void rejectsCorruptCompressedDeltaBlock() {
    final TreeDiff diff =
        new TreeDiff(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new DeltaEntry(4, sequentialBytes(1_000))));
    final Bytes serialized = TreeDiffCodec.serialize(diff);
    final MutableBytes corrupted = serialized.mutableCopy();
    // The last bytes of the compressed block, past its frame header
    for (int i = serialized.size() - 8; i < serialized.size(); i++) {
      corrupted.set(i, (byte) 0xff);
    }

    assertThatThrownBy(() -> TreeDiffCodec.deserialize(corrupted))
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
            List.of(
                patch(4, large + 1, 30, 1 << 30, 2, IntList.of(0, (1 << 30) - 1)),
                patch(4, largest - 1, 3, 8, 2, IntList.of(5))),
            List.of(new ZeroedEntry(large, HASH_A), new ZeroedEntry(largest, HASH_B)),
            List.of(new CopiedEntry(largest, large)),
            List.of());

    assertThat(TreeDiffCodec.deserialize(TreeDiffCodec.serialize(diff))).isEqualTo(diff);
  }

  private static TreeDiff sampleDiff() {
    return new TreeDiff(
        List.of(new LeafEntry(5, Bytes.of(1, 2, 3))),
        List.of(new SupernodeEntry(3, 7, 2, HASH_A)),
        List.of(patch(3, 6, 2, 4, 3, IntList.of(2))),
        List.of(new ZeroedEntry(13, HASH_B)),
        List.of(new CopiedEntry(13, 5)),
        List.of(new DeltaEntry(12, new byte[] {1, 2, 3})));
  }

  /** A patch whose written elements hold distinct bytes. */
  private static PatchEntry patch(
      final int fieldIndex,
      final long gIndex,
      final int depth,
      final int elementCount,
      final int elementSize,
      final IntList elementIndices) {
    final byte[] elements = new byte[elementIndices.size() * elementSize];
    for (int i = 0; i < elements.length; i++) {
      elements[i] = (byte) (gIndex + i);
    }
    return new PatchEntry(
        fieldIndex, gIndex, depth, elementCount, elementSize, elementIndices, Bytes.wrap(elements));
  }

  private static byte[] sequentialBytes(final int length) {
    final byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) i;
    }
    return bytes;
  }
}
