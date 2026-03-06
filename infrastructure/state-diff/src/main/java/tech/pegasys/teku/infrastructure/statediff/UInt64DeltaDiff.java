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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;

/**
 * Delta diff for UInt64 lists (balances, inactivity_scores). Computes element-wise deltas and
 * encodes them using zigzag varint encoding. Handles list growth (new validators appended).
 */
public class UInt64DeltaDiff implements StateDiff {

  private static final byte FORMAT_VERSION = 1;
  static final int UINT64_SIZE = 8;

  private final int baseCount;
  private final int targetCount;
  private final byte[] encodedDeltas;
  private final Bytes appendedValues;

  UInt64DeltaDiff(
      final int baseCount,
      final int targetCount,
      final byte[] encodedDeltas,
      final Bytes appendedValues) {
    this.baseCount = baseCount;
    this.targetCount = targetCount;
    this.encodedDeltas = encodedDeltas;
    this.appendedValues = appendedValues;
  }

  @Override
  public Bytes apply(final Bytes baseSsz) {
    final int resultSize = targetCount * UINT64_SIZE;
    final MutableBytes result = MutableBytes.create(resultSize);
    final ByteBuffer baseBuf =
        ByteBuffer.wrap(baseSsz.toArrayUnsafe()).order(ByteOrder.LITTLE_ENDIAN);
    final ByteBuffer resultBuf =
        ByteBuffer.wrap(result.toArrayUnsafe()).order(ByteOrder.LITTLE_ENDIAN);

    // Apply deltas to existing elements
    int deltaOffset = 0;
    final int commonCount = Math.min(baseCount, targetCount);
    for (int i = 0; i < commonCount; i++) {
      final long baseVal = baseBuf.getLong();
      final long delta = readZigzagVarint(encodedDeltas, deltaOffset);
      deltaOffset = advanceVarint(encodedDeltas, deltaOffset);
      resultBuf.putLong(baseVal + delta);
    }

    // Append new elements if target has more validators
    if (appendedValues.size() > 0) {
      appendedValues.copyTo(result, commonCount * UINT64_SIZE);
    }

    return result;
  }

  @Override
  public Bytes serialize() {
    // Format: [1B version][4B baseCount][4B targetCount][4B deltaLen][deltas...][appendedValues...]
    final int totalSize = 1 + 4 + 4 + 4 + encodedDeltas.length + appendedValues.size();
    final ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
    buf.put(FORMAT_VERSION);
    buf.putInt(baseCount);
    buf.putInt(targetCount);
    buf.putInt(encodedDeltas.length);
    buf.put(encodedDeltas);
    buf.put(appendedValues.toArrayUnsafe());
    buf.flip();
    return Bytes.wrapByteBuffer(buf);
  }

  // Zigzag encoding: maps signed to unsigned so small deltas (positive or negative) use few bytes
  static long zigzagEncode(final long value) {
    return (value << 1) ^ (value >> 63);
  }

  static long zigzagDecode(final long value) {
    return (value >>> 1) ^ -(value & 1);
  }

  static void writeVarint(final ByteArrayOutputStream out, final long value) {
    long v = value;
    while ((v & ~0x7FL) != 0) {
      out.write((int) ((v & 0x7F) | 0x80));
      v >>>= 7;
    }
    out.write((int) (v & 0x7F));
  }

  static long readZigzagVarint(final byte[] data, final int startOffset) {
    long result = 0;
    int shift = 0;
    int offset = startOffset;
    while (offset < data.length) {
      final byte b = data[offset];
      result |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return zigzagDecode(result);
      }
      shift += 7;
      offset++;
    }
    throw new IllegalStateException("Truncated varint at offset " + startOffset);
  }

  static int advanceVarint(final byte[] data, final int startOffset) {
    int offset = startOffset;
    while (offset < data.length) {
      if ((data[offset] & 0x80) == 0) {
        return offset + 1;
      }
      offset++;
    }
    throw new IllegalStateException("Truncated varint at offset " + startOffset);
  }

  public static class Schema implements StateDiffSchema {

    @Override
    public StateDiff computeDiff(final Bytes baseSsz, final Bytes targetSsz) {
      final int baseCount = baseSsz.size() / UINT64_SIZE;
      final int targetCount = targetSsz.size() / UINT64_SIZE;
      final int commonCount = Math.min(baseCount, targetCount);

      final ByteBuffer baseBuf =
          ByteBuffer.wrap(baseSsz.toArrayUnsafe()).order(ByteOrder.LITTLE_ENDIAN);
      final ByteBuffer targetBuf =
          ByteBuffer.wrap(targetSsz.toArrayUnsafe()).order(ByteOrder.LITTLE_ENDIAN);

      final ByteArrayOutputStream deltaStream = new ByteArrayOutputStream();

      for (int i = 0; i < commonCount; i++) {
        final long baseVal = baseBuf.getLong();
        final long targetVal = targetBuf.getLong();
        writeVarint(deltaStream, zigzagEncode(targetVal - baseVal));
      }

      // Collect appended elements (new validators)
      final Bytes appended;
      if (targetCount > baseCount) {
        appended = targetSsz.slice(baseCount * UINT64_SIZE);
      } else {
        appended = Bytes.EMPTY;
      }

      return new UInt64DeltaDiff(baseCount, targetCount, deltaStream.toByteArray(), appended);
    }

    @Override
    public StateDiff deserialize(final Bytes serialized) {
      final ByteBuffer buf =
          ByteBuffer.wrap(serialized.toArrayUnsafe()).order(ByteOrder.LITTLE_ENDIAN);
      final byte version = buf.get();
      if (version != FORMAT_VERSION) {
        throw new IllegalArgumentException(
            "Unsupported UInt64DeltaDiff format version: " + version);
      }

      final int baseCount = buf.getInt();
      final int targetCount = buf.getInt();
      final int deltaLen = buf.getInt();
      final byte[] deltas = new byte[deltaLen];
      buf.get(deltas);

      final int appendedSize = buf.remaining();
      final byte[] appended = new byte[appendedSize];
      if (appendedSize > 0) {
        buf.get(appended);
      }

      return new UInt64DeltaDiff(baseCount, targetCount, deltas, Bytes.wrap(appended));
    }
  }
}
