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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Unsigned LEB128 varints: 7 bits per byte, least significant group first, high bit set on every
 * byte except the last. A 64-bit value takes at most 10 bytes. Signed values are zigzag-mapped
 * first so small magnitudes of either sign stay short.
 */
public final class VarInts {

  static final int MAX_VARINT_BYTES = 10;

  private VarInts() {}

  public static void writeUnsigned(final ByteArrayOutputStream out, final long value) {
    long remaining = value;
    while ((remaining & ~0x7FL) != 0) {
      out.write((int) ((remaining & 0x7F) | 0x80));
      remaining >>>= 7;
    }
    out.write((int) remaining);
  }

  public static long readUnsigned(final ByteBuffer in) {
    long result = 0;
    for (int i = 0; i < MAX_VARINT_BYTES; i++) {
      if (!in.hasRemaining()) {
        throw new IllegalArgumentException("Truncated varint");
      }
      final byte b = in.get();
      result |= (long) (b & 0x7F) << (7 * i);
      if ((b & 0x80) == 0) {
        return result;
      }
    }
    throw new IllegalArgumentException("Varint longer than " + MAX_VARINT_BYTES + " bytes");
  }

  public static void writeSigned(final ByteArrayOutputStream out, final long value) {
    writeUnsigned(out, zigzagEncode(value));
  }

  public static long readSigned(final ByteBuffer in) {
    return zigzagDecode(readUnsigned(in));
  }

  public static long zigzagEncode(final long v) {
    return (v << 1) ^ (v >> 63);
  }

  public static long zigzagDecode(final long v) {
    return (v >>> 1) ^ -(v & 1);
  }
}
