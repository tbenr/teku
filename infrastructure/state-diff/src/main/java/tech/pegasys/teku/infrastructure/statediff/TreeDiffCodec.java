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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.CopiedEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.DeltaEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.LeafEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.SupernodeEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.ZeroedEntry;

/**
 * Binary encoding of a {@link TreeDiff}.
 *
 * <p>Layout: {@code u8 version=1}, then the sections leaves, supernodes, zeroed, copied and deltas,
 * each starting with a varint count. Leaves and zeroed entries are sorted by gIndex and store each
 * gIndex as an unsigned delta from the previous one (the first from 0). Supernodes are grouped by
 * field index (the section count is the group count); each group stores its field index, its entry
 * count and, per entry, the gIndex delta, the depth and the 32-byte hash. Copied entries store both
 * gIndexes, delta entries their field index and length-prefixed bytes, and leaf data is
 * length-prefixed.
 */
public final class TreeDiffCodec {

  static final int VERSION = 1;

  private TreeDiffCodec() {}

  public static Bytes serialize(final TreeDiff diff) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(VERSION);
    writeLeaves(out, diff.leaves());
    writeSupernodes(out, diff.supernodes());
    writeZeroed(out, diff.zeroed());
    writeCopied(out, diff.copied());
    writeDeltas(out, diff.deltas());
    return Bytes.wrap(out.toByteArray());
  }

  public static TreeDiff deserialize(final Bytes bytes) {
    final ByteBuffer in = openVersioned(bytes);
    final List<LeafEntry> leaves = readLeaves(in);
    final List<SupernodeEntry> supernodes = readSupernodes(in);
    final List<ZeroedEntry> zeroed = readZeroed(in);
    final List<CopiedEntry> copied = readCopied(in);
    final List<DeltaEntry> deltas = readDeltas(in);
    checkNoTrailingBytes(in);
    return new TreeDiff(leaves, supernodes, zeroed, copied, deltas);
  }

  /**
   * The hashes of the supernode entries of a serialized diff, in entry order, as {@code
   * deserialize(bytes).supernodes()} lists them. The other sections are walked without
   * materialising leaf data or delta bytes, so exactly the inputs {@link #deserialize} rejects are
   * rejected.
   */
  public static List<Bytes32> supernodeHashes(final Bytes bytes) {
    final ByteBuffer in = openVersioned(bytes);
    skipLeaves(in);
    final List<Bytes32> hashes = new ArrayList<>();
    for (final SupernodeEntry entry : readSupernodes(in)) {
      hashes.add(entry.hash());
    }
    skipZeroed(in);
    readCopied(in);
    skipDeltas(in);
    checkNoTrailingBytes(in);
    return hashes;
  }

  private static ByteBuffer openVersioned(final Bytes bytes) {
    final ByteBuffer in = ByteBuffer.wrap(bytes.toArrayUnsafe());
    if (!in.hasRemaining()) {
      throw new IllegalArgumentException("Empty tree diff");
    }
    final int version = Byte.toUnsignedInt(in.get());
    if (version != VERSION) {
      throw new IllegalArgumentException("Unknown tree diff version " + version);
    }
    return in;
  }

  private static void checkNoTrailingBytes(final ByteBuffer in) {
    if (in.hasRemaining()) {
      throw new IllegalArgumentException(in.remaining() + " trailing bytes after tree diff");
    }
  }

  private static void writeLeaves(final ByteArrayOutputStream out, final List<LeafEntry> leaves) {
    VarInts.writeUnsigned(out, leaves.size());
    long previousGIndex = 0;
    for (final LeafEntry leaf : sorted(leaves, Comparator.comparingLong(LeafEntry::gIndex))) {
      VarInts.writeUnsigned(out, leaf.gIndex() - previousGIndex);
      previousGIndex = leaf.gIndex();
      writeLengthPrefixed(out, leaf.data().toArrayUnsafe());
    }
  }

  private static List<LeafEntry> readLeaves(final ByteBuffer in) {
    final int count = readCount(in);
    final List<LeafEntry> leaves = new ArrayList<>(count);
    long gIndex = 0;
    for (int i = 0; i < count; i++) {
      gIndex += VarInts.readUnsigned(in);
      leaves.add(new LeafEntry(gIndex, Bytes.wrap(readLengthPrefixed(in))));
    }
    return leaves;
  }

  private static void skipLeaves(final ByteBuffer in) {
    final int count = readCount(in);
    for (int i = 0; i < count; i++) {
      VarInts.readUnsigned(in);
      skipBytes(in, readInt(in));
    }
  }

  private static void writeSupernodes(
      final ByteArrayOutputStream out, final List<SupernodeEntry> supernodes) {
    final Map<Integer, List<SupernodeEntry>> groups = new TreeMap<>();
    for (final SupernodeEntry supernode : supernodes) {
      groups.computeIfAbsent(supernode.fieldIndex(), __ -> new ArrayList<>()).add(supernode);
    }
    VarInts.writeUnsigned(out, groups.size());
    for (final Map.Entry<Integer, List<SupernodeEntry>> group : groups.entrySet()) {
      VarInts.writeUnsigned(out, group.getKey());
      VarInts.writeUnsigned(out, group.getValue().size());
      long previousGIndex = 0;
      for (final SupernodeEntry supernode :
          sorted(group.getValue(), Comparator.comparingLong(SupernodeEntry::gIndex))) {
        VarInts.writeUnsigned(out, supernode.gIndex() - previousGIndex);
        previousGIndex = supernode.gIndex();
        VarInts.writeUnsigned(out, supernode.depth());
        out.writeBytes(supernode.hash().toArrayUnsafe());
      }
    }
  }

  private static List<SupernodeEntry> readSupernodes(final ByteBuffer in) {
    final int groupCount = readCount(in);
    final List<SupernodeEntry> supernodes = new ArrayList<>();
    for (int group = 0; group < groupCount; group++) {
      final int fieldIndex = readInt(in);
      final int count = readCount(in);
      long gIndex = 0;
      for (int i = 0; i < count; i++) {
        gIndex += VarInts.readUnsigned(in);
        final int depth = readInt(in);
        final Bytes32 hash = Bytes32.wrap(readBytes(in, Bytes32.SIZE));
        supernodes.add(new SupernodeEntry(fieldIndex, gIndex, depth, hash));
      }
    }
    return supernodes;
  }

  private static void writeZeroed(final ByteArrayOutputStream out, final List<ZeroedEntry> zeroed) {
    VarInts.writeUnsigned(out, zeroed.size());
    long previousGIndex = 0;
    for (final ZeroedEntry entry : sorted(zeroed, Comparator.comparingLong(ZeroedEntry::gIndex))) {
      VarInts.writeUnsigned(out, entry.gIndex() - previousGIndex);
      previousGIndex = entry.gIndex();
      out.writeBytes(entry.zeroRoot().toArrayUnsafe());
    }
  }

  private static List<ZeroedEntry> readZeroed(final ByteBuffer in) {
    final int count = readCount(in);
    final List<ZeroedEntry> zeroed = new ArrayList<>(count);
    long gIndex = 0;
    for (int i = 0; i < count; i++) {
      gIndex += VarInts.readUnsigned(in);
      zeroed.add(new ZeroedEntry(gIndex, Bytes32.wrap(readBytes(in, Bytes32.SIZE))));
    }
    return zeroed;
  }

  private static void skipZeroed(final ByteBuffer in) {
    final int count = readCount(in);
    for (int i = 0; i < count; i++) {
      VarInts.readUnsigned(in);
      skipBytes(in, Bytes32.SIZE);
    }
  }

  private static void writeCopied(final ByteArrayOutputStream out, final List<CopiedEntry> copied) {
    VarInts.writeUnsigned(out, copied.size());
    for (final CopiedEntry entry : copied) {
      VarInts.writeUnsigned(out, entry.targetGIndex());
      VarInts.writeUnsigned(out, entry.sourceGIndex());
    }
  }

  private static List<CopiedEntry> readCopied(final ByteBuffer in) {
    final int count = readCount(in);
    final List<CopiedEntry> copied = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      final long targetGIndex = VarInts.readUnsigned(in);
      final long sourceGIndex = VarInts.readUnsigned(in);
      copied.add(new CopiedEntry(targetGIndex, sourceGIndex));
    }
    return copied;
  }

  private static void writeDeltas(final ByteArrayOutputStream out, final List<DeltaEntry> deltas) {
    VarInts.writeUnsigned(out, deltas.size());
    for (final DeltaEntry entry : deltas) {
      VarInts.writeUnsigned(out, entry.fieldIndex());
      writeLengthPrefixed(out, entry.encodedDeltas());
    }
  }

  private static List<DeltaEntry> readDeltas(final ByteBuffer in) {
    final int count = readCount(in);
    final List<DeltaEntry> deltas = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      final int fieldIndex = readInt(in);
      deltas.add(new DeltaEntry(fieldIndex, readLengthPrefixed(in)));
    }
    return deltas;
  }

  private static void skipDeltas(final ByteBuffer in) {
    final int count = readCount(in);
    for (int i = 0; i < count; i++) {
      readInt(in);
      skipBytes(in, readInt(in));
    }
  }

  private static <T> List<T> sorted(final List<T> entries, final Comparator<T> comparator) {
    final List<T> copy = new ArrayList<>(entries);
    copy.sort(comparator);
    return copy;
  }

  private static void writeLengthPrefixed(final ByteArrayOutputStream out, final byte[] data) {
    VarInts.writeUnsigned(out, data.length);
    out.writeBytes(data);
  }

  private static byte[] readLengthPrefixed(final ByteBuffer in) {
    return readBytes(in, readInt(in));
  }

  private static byte[] readBytes(final ByteBuffer in, final int length) {
    checkAvailable(in, length);
    final byte[] data = new byte[length];
    in.get(data);
    return data;
  }

  private static void skipBytes(final ByteBuffer in, final int length) {
    checkAvailable(in, length);
    in.position(in.position() + length);
  }

  private static void checkAvailable(final ByteBuffer in, final int length) {
    if (in.remaining() < length) {
      throw new IllegalArgumentException(
          "Truncated tree diff: need " + length + " bytes, have " + in.remaining());
    }
  }

  /**
   * Reads an element count. Every element takes at least one byte, so a count larger than the
   * remaining input is rejected before anything is allocated for it.
   */
  private static int readCount(final ByteBuffer in) {
    final int count = readInt(in);
    if (count > in.remaining()) {
      throw new IllegalArgumentException(
          "Count " + count + " exceeds remaining " + in.remaining() + " bytes");
    }
    return count;
  }

  private static int readInt(final ByteBuffer in) {
    final long value = VarInts.readUnsigned(in);
    if (value < 0 || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Value out of int range: " + Long.toUnsignedString(value));
    }
    return (int) value;
  }
}
