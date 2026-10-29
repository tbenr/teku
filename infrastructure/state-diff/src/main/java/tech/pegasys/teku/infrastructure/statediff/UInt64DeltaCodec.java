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

import java.nio.ByteBuffer;

/**
 * Element-wise delta encoding of uint64 lists (balances, inactivity scores). Elements present in
 * both lists are stored as zigzag varint deltas (wrapping arithmetic); elements appended beyond the
 * base length are stored as plain unsigned varints. A shrinking list keeps the surviving prefix.
 *
 * <p>Layout: {@code varint(baseCount) varint(targetCount) signed(target[i] - base[i])...
 * unsigned(target[j])...}. There is no format byte: the enclosing payload is versioned.
 */
public final class UInt64DeltaCodec {

  private UInt64DeltaCodec() {}

  /**
   * Encodes {@code target} against {@code base} into an array of exactly the encoded size: a first
   * pass sums the varint lengths, a second writes them, so a multi-MB list is neither written byte
   * by byte through a stream nor copied to trim it.
   */
  public static byte[] encode(final long[] base, final long[] target) {
    final int commonCount = Math.min(base.length, target.length);
    long size = (long) VarInts.unsignedLength(base.length) + VarInts.unsignedLength(target.length);
    for (int i = 0; i < commonCount; i++) {
      size += VarInts.unsignedLength(VarInts.zigzagEncode(target[i] - base[i]));
    }
    for (int i = base.length; i < target.length; i++) {
      size += VarInts.unsignedLength(target[i]);
    }
    if (size > VarInts.MAX_ARRAY_LENGTH) {
      throw new IllegalArgumentException("Deltas of " + target.length + " elements too large");
    }
    final byte[] out = new byte[(int) size];
    int position = VarInts.writeUnsigned(out, 0, base.length);
    position = VarInts.writeUnsigned(out, position, target.length);
    for (int i = 0; i < commonCount; i++) {
      position = VarInts.writeSigned(out, position, target[i] - base[i]);
    }
    for (int i = base.length; i < target.length; i++) {
      position = VarInts.writeUnsigned(out, position, target[i]);
    }
    return out;
  }

  /** The target {@code encoded} was encoded for against {@code base}, in a new array. */
  public static long[] apply(final long[] base, final byte[] encoded) {
    return apply(base, encoded, false);
  }

  /**
   * The target {@code encoded} was encoded for against {@code values}, written over {@code values}
   * when it has as many elements, else in a new array. {@code values} holds no meaningful content
   * afterwards unless it is the result, also when the encoding is rejected.
   */
  public static long[] applyInPlace(final long[] values, final byte[] encoded) {
    return apply(values, encoded, true);
  }

  private static long[] apply(final long[] base, final byte[] encoded, final boolean inPlace) {
    final ByteBuffer in = ByteBuffer.wrap(encoded);
    final long baseCount = VarInts.readUnsigned(in);
    if (baseCount != base.length) {
      throw new IllegalArgumentException(
          "Delta encoded against " + baseCount + " elements but base has " + base.length);
    }
    final long targetCount = VarInts.readUnsigned(in);
    // Every target element costs at least one byte, so a corrupt count is rejected before the
    // result array is allocated
    if (targetCount < 0 || targetCount > in.remaining()) {
      throw new IllegalArgumentException(
          "Invalid target count "
              + Long.toUnsignedString(targetCount)
              + " for "
              + in.remaining()
              + " remaining bytes");
    }
    // Each element is read before it is written, so the base can be the result
    final long[] result =
        inPlace && targetCount == base.length ? base : new long[(int) targetCount];
    final int commonCount = Math.min(base.length, result.length);
    for (int i = 0; i < commonCount; i++) {
      result[i] = base[i] + VarInts.readSigned(in);
    }
    for (int i = base.length; i < result.length; i++) {
      result[i] = VarInts.readUnsigned(in);
    }
    if (in.hasRemaining()) {
      throw new IllegalArgumentException(in.remaining() + " trailing bytes after deltas");
    }
    return result;
  }
}
