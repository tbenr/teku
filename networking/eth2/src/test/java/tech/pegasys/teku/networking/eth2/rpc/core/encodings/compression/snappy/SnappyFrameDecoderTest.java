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

package tech.pegasys.teku.networking.eth2.rpc.core.encodings.compression.snappy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.networking.eth2.rpc.core.encodings.compression.exceptions.CompressionException;

public class SnappyFrameDecoderTest {

  private static final Bytes STREAM_IDENTIFIER = Bytes.fromHexString("0xff060000734e61507059");
  // checksum plus snappy's worst-case encoded length of a 65536-byte block: 32 + n + n / 6
  private static final int MAX_COMPRESSED_CHUNK_LENGTH = 4 + 32 + 65536 + 65536 / 6;

  @Test
  void acceptsCompressedChunkHeaderAtMaxEncodedLength() throws Exception {
    final ByteBuf in = compressedChunkHeader(MAX_COMPRESSED_CHUNK_LENGTH);
    final SnappyFrameDecoder decoder = new AircompressorSnappyFrameDecoder();

    assertThat(decoder.decodeOneMessage(in)).isEmpty();

    decoder.close();
    in.release();
  }

  @Test
  void rejectsCompressedChunkLongerThanMaxEncodedLength() {
    final ByteBuf in = compressedChunkHeader(MAX_COMPRESSED_CHUNK_LENGTH + 1);
    final SnappyFrameDecoder decoder = new AircompressorSnappyFrameDecoder();

    assertThatThrownBy(() -> decoder.decodeOneMessage(in)).isInstanceOf(CompressionException.class);

    in.release();
  }

  private static ByteBuf compressedChunkHeader(final int chunkLength) {
    final ByteBuf in = Unpooled.buffer();
    in.writeBytes(STREAM_IDENTIFIER.toArrayUnsafe());
    in.writeByte(0x00);
    in.writeMediumLE(chunkLength);
    return in;
  }
}
