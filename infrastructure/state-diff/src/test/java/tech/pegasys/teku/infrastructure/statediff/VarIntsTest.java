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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class VarIntsTest {

  @Test
  void roundTripsUnsignedBoundaries() {
    final long[] values = {0L, 127L, 128L, 16383L, 16384L, Long.MAX_VALUE, -1L};
    for (final long value : values) {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      VarInts.writeUnsigned(out, value);
      final ByteBuffer in = ByteBuffer.wrap(out.toByteArray());
      assertThat(VarInts.readUnsigned(in)).isEqualTo(value);
      assertThat(in.hasRemaining()).isFalse();
    }
  }

  @Test
  void roundTripsSignedBoundaries() {
    final long[] values = {0L, 1L, -1L, 63L, -64L, 64L, Long.MIN_VALUE, Long.MAX_VALUE};
    for (final long value : values) {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      VarInts.writeSigned(out, value);
      final ByteBuffer in = ByteBuffer.wrap(out.toByteArray());
      assertThat(VarInts.readSigned(in)).isEqualTo(value);
      assertThat(in.hasRemaining()).isFalse();
    }
  }

  @Test
  void zigzagKnownValues() {
    assertThat(VarInts.zigzagEncode(0L)).isEqualTo(0L);
    assertThat(VarInts.zigzagEncode(-1L)).isEqualTo(1L);
    assertThat(VarInts.zigzagEncode(1L)).isEqualTo(2L);
    assertThat(VarInts.zigzagEncode(-2L)).isEqualTo(3L);
    assertThat(VarInts.zigzagEncode(2147483647L)).isEqualTo(4294967294L);
    assertThat(VarInts.zigzagEncode(-2147483648L)).isEqualTo(4294967295L);

    assertThat(VarInts.zigzagDecode(0L)).isEqualTo(0L);
    assertThat(VarInts.zigzagDecode(1L)).isEqualTo(-1L);
    assertThat(VarInts.zigzagDecode(2L)).isEqualTo(1L);
    assertThat(VarInts.zigzagDecode(3L)).isEqualTo(-2L);
    assertThat(VarInts.zigzagDecode(4294967294L)).isEqualTo(2147483647L);
    assertThat(VarInts.zigzagDecode(4294967295L)).isEqualTo(-2147483648L);
  }

  @Test
  void encodingSizes() {
    assertThat(unsignedEncodedSize(0L)).isEqualTo(1);
    assertThat(unsignedEncodedSize(127L)).isEqualTo(1);
    assertThat(unsignedEncodedSize(128L)).isEqualTo(2);
  }

  @Test
  void rejectsTruncatedInput() {
    assertThatThrownBy(() -> VarInts.readUnsigned(ByteBuffer.wrap(new byte[] {(byte) 0x80})))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMoreThanTenBytes() {
    final byte[] eleven = new byte[11];
    Arrays.fill(eleven, (byte) 0x80);

    assertThatThrownBy(() -> VarInts.readUnsigned(ByteBuffer.wrap(eleven)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static int unsignedEncodedSize(final long value) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    VarInts.writeUnsigned(out, value);
    return out.size();
  }
}
