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

import static com.google.common.base.Preconditions.checkArgument;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffCodec;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * Binary encoding of a {@link StateDiffNodeRecord}.
 *
 * <p>Layout: {@code u8 version=2}, {@code u64 blockSlot}, 32-byte stateRoot, {@code u64
 * firstEpoch}, {@code u64 lastEpoch}, {@code u8 level}, {@code u8 snapshot}, 32-byte
 * parentNodeRoot, then the payload as the remaining bytes. Integers are big-endian. The header has
 * a fixed size, so {@link #patchLastEpoch} rewrites the last epoch in place without touching the
 * payload.
 */
public final class StateDiffNodeRecordCodec {

  static final int VERSION = 2;

  private static final int VERSION_OFFSET = 0;
  private static final int BLOCK_SLOT_OFFSET = VERSION_OFFSET + 1;
  private static final int STATE_ROOT_OFFSET = BLOCK_SLOT_OFFSET + Long.BYTES;
  private static final int FIRST_EPOCH_OFFSET = STATE_ROOT_OFFSET + Bytes32.SIZE;
  private static final int LAST_EPOCH_OFFSET = FIRST_EPOCH_OFFSET + Long.BYTES;
  private static final int LEVEL_OFFSET = LAST_EPOCH_OFFSET + Long.BYTES;
  private static final int SNAPSHOT_OFFSET = LEVEL_OFFSET + 1;
  private static final int PARENT_NODE_ROOT_OFFSET = SNAPSHOT_OFFSET + 1;
  private static final int HEADER_SIZE = PARENT_NODE_ROOT_OFFSET + Bytes32.SIZE;

  private StateDiffNodeRecordCodec() {}

  public static Bytes encode(final StateDiffNodeRecord record) {
    final MutableBytes encoded = MutableBytes.create(HEADER_SIZE + record.payload().size());
    writeHeader(record, encoded);
    record.payload().copyTo(encoded, HEADER_SIZE);
    return encoded;
  }

  /** Encodes {@code diff} with room for a record header before it, for the encode taking it. */
  static TreeDiffCodec.Encoded encodePayload(final TreeDiff diff) {
    return TreeDiffCodec.encode(diff, HEADER_SIZE);
  }

  /**
   * Encodes {@code record}, whose payload is that of {@code payload} from {@link #encodePayload},
   * by writing its header into the room reserved for it: the payload is not copied.
   */
  static Bytes encode(final StateDiffNodeRecord record, final TreeDiffCodec.Encoded payload) {
    checkArgument(
        payload.prefixLength() == HEADER_SIZE,
        "Payload reserves %s bytes, not the %s byte header",
        payload.prefixLength(),
        HEADER_SIZE);
    writeHeader(record, payload.bytes());
    return payload.bytes();
  }

  private static void writeHeader(final StateDiffNodeRecord record, final MutableBytes encoded) {
    encoded.set(VERSION_OFFSET, (byte) VERSION);
    encoded.setLong(BLOCK_SLOT_OFFSET, record.blockSlot().longValue());
    encoded.set(STATE_ROOT_OFFSET, record.stateRoot());
    encoded.setLong(FIRST_EPOCH_OFFSET, record.firstEpoch().longValue());
    encoded.setLong(LAST_EPOCH_OFFSET, record.lastEpoch().longValue());
    encoded.set(LEVEL_OFFSET, (byte) record.level());
    encoded.set(SNAPSHOT_OFFSET, (byte) (record.snapshot() ? 1 : 0));
    encoded.set(PARENT_NODE_ROOT_OFFSET, record.parentNodeRoot());
  }

  public static StateDiffNodeRecord decode(final Bytes encoded) {
    checkHeader(encoded);
    final int snapshot = Byte.toUnsignedInt(encoded.get(SNAPSHOT_OFFSET));
    checkArgument(snapshot <= 1, "Invalid snapshot flag %s", snapshot);
    return new StateDiffNodeRecord(
        readUInt64(encoded, BLOCK_SLOT_OFFSET),
        Bytes32.wrap(encoded.slice(STATE_ROOT_OFFSET, Bytes32.SIZE)),
        readUInt64(encoded, FIRST_EPOCH_OFFSET),
        readUInt64(encoded, LAST_EPOCH_OFFSET),
        Byte.toUnsignedInt(encoded.get(LEVEL_OFFSET)),
        snapshot == 1,
        Bytes32.wrap(encoded.slice(PARENT_NODE_ROOT_OFFSET, Bytes32.SIZE)),
        // Read in place by the tree diff codec, without copying it out of the record
        TreeDiffCodec.payloadView(
            encoded.toArrayUnsafe(), HEADER_SIZE, encoded.size() - HEADER_SIZE));
  }

  /** Returns a copy of {@code encoded} with only its last epoch replaced by {@code lastEpoch}. */
  public static Bytes patchLastEpoch(final Bytes encoded, final UInt64 lastEpoch) {
    checkHeader(encoded);
    final MutableBytes patched = encoded.mutableCopy();
    patched.setLong(LAST_EPOCH_OFFSET, lastEpoch.longValue());
    return patched;
  }

  private static void checkHeader(final Bytes encoded) {
    checkArgument(!encoded.isEmpty(), "Empty state diff node record");
    final int version = Byte.toUnsignedInt(encoded.get(VERSION_OFFSET));
    checkArgument(version == VERSION, "Unknown state diff node record version %s", version);
    checkArgument(
        encoded.size() >= HEADER_SIZE,
        "State diff node record of %s bytes is shorter than its %s byte header",
        encoded.size(),
        HEADER_SIZE);
  }

  private static UInt64 readUInt64(final Bytes encoded, final int offset) {
    return UInt64.fromLongBits(encoded.getLong(offset));
  }
}
