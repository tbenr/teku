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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.LeafEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffCodec;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

class StateDiffNodeRecordCodecTest {

  // u8 version, u64 blockSlot, 32 stateRoot, u64 firstEpoch, u64 lastEpoch, u8 level, u8 snapshot,
  // 32 parentNodeRoot
  private static final int HEADER_SIZE = 1 + 8 + 32 + 8 + 8 + 1 + 1 + 32;
  private static final int LAST_EPOCH_OFFSET = 1 + 8 + 32 + 8;
  private static final int PARENT_NODE_ROOT_OFFSET = HEADER_SIZE - 32;

  private final StateDiffNodeRecord diffRecord =
      new StateDiffNodeRecord(
          UInt64.valueOf(1_234_567),
          Bytes32.fromHexString("0x01"),
          UInt64.valueOf(38_580),
          UInt64.valueOf(38_581),
          3,
          false,
          Bytes32.fromHexString("0x03"),
          Bytes.fromHexString("0xdeadbeef"));

  private final StateDiffNodeRecord snapshotRecord =
      new StateDiffNodeRecord(
          UInt64.MAX_VALUE,
          Bytes32.fromHexString("0x04"),
          UInt64.ZERO,
          UInt64.valueOf(2047),
          0,
          true,
          Bytes32.ZERO,
          Bytes.EMPTY);

  @Test
  void roundTripsRecord() {
    final Bytes encoded = StateDiffNodeRecordCodec.encode(diffRecord);

    assertThat(encoded.size()).isEqualTo(HEADER_SIZE + diffRecord.payload().size());
    assertThat(encoded.get(0)).isEqualTo((byte) 2);
    assertThat(encoded.slice(1, 8)).isEqualTo(Bytes.fromHexString("0x000000000012d687"));
    assertThat(encoded.slice(PARENT_NODE_ROOT_OFFSET, 32)).isEqualTo(diffRecord.parentNodeRoot());
    assertThat(encoded.slice(HEADER_SIZE)).isEqualTo(diffRecord.payload());
    assertThat(StateDiffNodeRecordCodec.decode(encoded)).isEqualTo(diffRecord);
    assertThat(StateDiffNodeRecordCodec.decode(StateDiffNodeRecordCodec.encode(snapshotRecord)))
        .isEqualTo(snapshotRecord);
  }

  @Test
  void encodesIntoTheRoomReservedBeforeAnEncodedPayload() {
    final TreeDiff diff =
        new TreeDiff(
            List.of(new LeafEntry(5, Bytes.of(1, 2, 3))),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    final TreeDiffCodec.Encoded payload = StateDiffNodeRecordCodec.encodePayload(diff);
    final StateDiffNodeRecord record =
        new StateDiffNodeRecord(
            diffRecord.blockSlot(),
            diffRecord.stateRoot(),
            diffRecord.firstEpoch(),
            diffRecord.lastEpoch(),
            diffRecord.level(),
            diffRecord.snapshot(),
            diffRecord.parentNodeRoot(),
            payload.payload());

    final Bytes encoded = StateDiffNodeRecordCodec.encode(record, payload);

    assertThat(encoded).isEqualTo(StateDiffNodeRecordCodec.encode(record));
    assertThat(encoded.toArrayUnsafe()).isSameAs(payload.bytes().toArrayUnsafe());
    assertThat(TreeDiffCodec.deserialize(StateDiffNodeRecordCodec.decode(encoded).payload()))
        .isEqualTo(diff);
    assertThatThrownBy(() -> StateDiffNodeRecordCodec.encode(record, TreeDiffCodec.encode(diff, 0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void patchLastEpochOnlyChangesThatField() {
    final Bytes encoded = StateDiffNodeRecordCodec.encode(diffRecord);
    final UInt64 newLastEpoch = UInt64.valueOf(40_000);

    final Bytes patched = StateDiffNodeRecordCodec.patchLastEpoch(encoded, newLastEpoch);

    assertThat(StateDiffNodeRecordCodec.decode(patched))
        .isEqualTo(
            new StateDiffNodeRecord(
                diffRecord.blockSlot(),
                diffRecord.stateRoot(),
                diffRecord.firstEpoch(),
                newLastEpoch,
                diffRecord.level(),
                diffRecord.snapshot(),
                diffRecord.parentNodeRoot(),
                diffRecord.payload()));
    assertThat(patched.size()).isEqualTo(encoded.size());
    assertThat(patched.slice(0, LAST_EPOCH_OFFSET)).isEqualTo(encoded.slice(0, LAST_EPOCH_OFFSET));
    assertThat(patched.slice(LAST_EPOCH_OFFSET, 8))
        .isEqualTo(Bytes.ofUnsignedLong(newLastEpoch.longValue()));
    assertThat(patched.slice(LAST_EPOCH_OFFSET + 8))
        .isEqualTo(encoded.slice(LAST_EPOCH_OFFSET + 8));
    assertThat(StateDiffNodeRecordCodec.decode(encoded)).isEqualTo(diffRecord);
  }

  @Test
  void rejectsUnknownVersionAndTruncatedRecord() {
    final Bytes encoded = StateDiffNodeRecordCodec.encode(diffRecord);
    final Bytes truncated = encoded.slice(0, HEADER_SIZE - 1);

    // Version 1 too: a database holding it is rejected when it is opened
    for (final int version : List.of(1, 3)) {
      final Bytes unknownVersion = Bytes.concatenate(Bytes.of(version), encoded.slice(1));
      assertThatThrownBy(() -> StateDiffNodeRecordCodec.decode(unknownVersion))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Unknown state diff node record version " + version);
      assertThatThrownBy(() -> StateDiffNodeRecordCodec.patchLastEpoch(unknownVersion, UInt64.ONE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Unknown state diff node record version " + version);
    }
    assertThatThrownBy(() -> StateDiffNodeRecordCodec.decode(truncated))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StateDiffNodeRecordCodec.patchLastEpoch(truncated, UInt64.ONE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StateDiffNodeRecordCodec.decode(Bytes.EMPTY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void snapshotRecordRequiresZeroParent() {
    assertThatThrownBy(
            () ->
                new StateDiffNodeRecord(
                    UInt64.ONE,
                    Bytes32.ZERO,
                    UInt64.ZERO,
                    UInt64.ZERO,
                    0,
                    true,
                    Bytes32.fromHexString("0x05"),
                    Bytes.EMPTY))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
