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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;

/**
 * Binary patch diff at 32-byte granularity. Compares base/target SSZ bytes in 32B chunks and stores
 * a list of (offset, length, newBytes) patches for changed regions.
 */
public class SimpleSszDiff implements StateDiff {

  static final int CHUNK_SIZE = 32;
  private static final byte FORMAT_VERSION = 1;

  private final int targetSize;
  private final List<Patch> patches;

  SimpleSszDiff(final int targetSize, final List<Patch> patches) {
    this.targetSize = targetSize;
    this.patches = patches;
  }

  @Override
  public Bytes apply(final Bytes baseSsz) {
    final MutableBytes result = MutableBytes.create(targetSize);
    // Copy base up to min of base size and target size
    final int copyLen = Math.min(baseSsz.size(), targetSize);
    baseSsz.slice(0, copyLen).copyTo(result, 0);

    // Apply patches
    for (final Patch patch : patches) {
      patch.data().copyTo(result, patch.offset());
    }

    return result;
  }

  @Override
  public Bytes serialize() {
    // Format: [1B version][4B targetSize][4B patchCount][patches...]
    // Each patch: [4B offset][4B length][data...]
    int totalSize = 1 + 4 + 4;
    for (final Patch patch : patches) {
      totalSize += 4 + 4 + patch.data().size();
    }

    final ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
    buf.put(FORMAT_VERSION);
    buf.putInt(targetSize);
    buf.putInt(patches.size());
    for (final Patch patch : patches) {
      buf.putInt(patch.offset());
      buf.putInt(patch.data().size());
      buf.put(patch.data().toArrayUnsafe());
    }
    buf.flip();
    return Bytes.wrapByteBuffer(buf);
  }

  record Patch(int offset, Bytes data) {}

  public static class Schema implements StateDiffSchema {

    @Override
    public StateDiff computeDiff(final Bytes baseSsz, final Bytes targetSsz) {
      final List<Patch> patches = new ArrayList<>();
      final int baseSize = baseSsz.size();
      final int targetSize = targetSsz.size();
      final int commonLen = Math.min(baseSize, targetSize);

      // Compare in CHUNK_SIZE chunks
      int pos = 0;
      while (pos < commonLen) {
        final int chunkEnd = Math.min(pos + CHUNK_SIZE, commonLen);
        if (!baseSsz.slice(pos, chunkEnd - pos).equals(targetSsz.slice(pos, chunkEnd - pos))) {
          // Start of a changed region - extend to cover consecutive changed chunks
          final int regionStart = pos;
          pos = chunkEnd;
          while (pos < commonLen) {
            final int nextEnd = Math.min(pos + CHUNK_SIZE, commonLen);
            if (baseSsz.slice(pos, nextEnd - pos).equals(targetSsz.slice(pos, nextEnd - pos))) {
              break;
            }
            pos = nextEnd;
          }
          patches.add(new Patch(regionStart, targetSsz.slice(regionStart, pos - regionStart)));
        } else {
          pos = chunkEnd;
        }
      }

      // If target is longer than base, append the tail
      if (targetSize > baseSize) {
        patches.add(new Patch(baseSize, targetSsz.slice(baseSize)));
      }

      return new SimpleSszDiff(targetSize, patches);
    }

    @Override
    public StateDiff deserialize(final Bytes serialized) {
      final ByteBuffer buf =
          ByteBuffer.wrap(serialized.toArrayUnsafe()).order(ByteOrder.LITTLE_ENDIAN);
      final byte version = buf.get();
      if (version != FORMAT_VERSION) {
        throw new IllegalArgumentException("Unsupported SimpleSszDiff format version: " + version);
      }

      final int targetSize = buf.getInt();
      final int patchCount = buf.getInt();
      final List<Patch> patches = new ArrayList<>(patchCount);

      for (int i = 0; i < patchCount; i++) {
        final int offset = buf.getInt();
        final int length = buf.getInt();
        final byte[] data = new byte[length];
        buf.get(data);
        patches.add(new Patch(offset, Bytes.wrap(data)));
      }

      return new SimpleSszDiff(targetSize, patches);
    }
  }
}
