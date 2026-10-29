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

import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
import io.airlift.compress.v3.zstd.ZstdJavaDecompressor;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.CopiedEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.DeltaEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.LeafEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.PatchEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.SupernodeEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.ZeroedEntry;

/**
 * Binary encoding of a {@link TreeDiff}.
 *
 * <p>Layout: {@code u8 version=2}, then the sections leaves, supernodes, patches, zeroed, copied
 * and deltas, each starting with a varint count. Leaves and zeroed entries are sorted by gIndex and
 * store each gIndex as an unsigned delta from the previous one (the first from 0). Supernodes are
 * grouped by field index (the section count is the group count); each group stores its field index,
 * its entry count and, per entry, the gIndex delta, the depth and the 32-byte hash. Patches are
 * grouped the same way; each group stores its field index, the element size and its entry count,
 * and each entry the gIndex delta, the depth, the element count, the number of written elements,
 * each written element's index as the gap from the previous one plus one (the first from -1), then
 * the written elements' bytes back to back. Copied entries store both gIndexes and leaf data is
 * length-prefixed. Each delta entry stores its field index, the length of its {@link
 * UInt64DeltaCodec} bytes, then those bytes compressed as one zstd frame, prefixed by the
 * compressed length.
 *
 * <p>Version 1, the same layout with the delta bytes stored uncompressed, is not read: the only
 * databases holding it were written by earlier experimental builds and must be re-synced.
 */
public final class TreeDiffCodec {

  static final int VERSION = 2;

  /**
   * Why a version 1 payload is rejected; also used for version 1 node records, which hold version 1
   * payloads.
   */
  public static final String VERSION_ONE_MESSAGE =
      "State diff node payload version 1, written by an earlier experimental build, is no longer"
          + " readable: the database must be re-synced into a fresh data directory";

  /**
   * zstd stores at least 4 bytes for a block of at most 128 KiB, so a frame decompresses to at most
   * this many times its size; a raw length beyond that is corrupt and is not allocated.
   */
  private static final long MAX_DECOMPRESSION_RATIO = 32 * 1024;

  private static final int MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

  /** The most bytes a varint of an int value takes. */
  private static final int MAX_INT_VARINT_BYTES = 5;

  /** Stateless and level 3; the Java implementation, so payloads do not depend on a native one. */
  private static final ZstdJavaCompressor COMPRESSOR = new ZstdJavaCompressor();

  private TreeDiffCodec() {}

  /**
   * An encoded diff.
   *
   * @param payload the encoded diff
   * @param compressedDeltaBytes the compressed bytes of its delta entries, without their prefixes
   */
  public record Encoded(Bytes payload, long compressedDeltaBytes) {}

  public static Bytes serialize(final TreeDiff diff) {
    return encode(diff).payload();
  }

  /**
   * Encodes {@code diff} into one buffer sized for the worst case, compressing each delta entry
   * straight into it, and copies the result once to its exact size.
   */
  public static Encoded encode(final TreeDiff diff) {
    final List<LeafEntry> leaves =
        sorted(diff.leaves(), Comparator.comparingLong(LeafEntry::gIndex));
    final Map<Integer, List<SupernodeEntry>> supernodeGroups =
        groupByField(
            diff.supernodes(),
            SupernodeEntry::fieldIndex,
            Comparator.comparingLong(SupernodeEntry::gIndex));
    final Map<Integer, List<PatchEntry>> patchGroups =
        groupByField(
            diff.patches(), PatchEntry::fieldIndex, Comparator.comparingLong(PatchEntry::gIndex));
    final List<ZeroedEntry> zeroed =
        sorted(diff.zeroed(), Comparator.comparingLong(ZeroedEntry::gIndex));

    final Writer out =
        new Writer(maxEncodedSize(diff, supernodeGroups.size() + patchGroups.size()));
    out.writeByte(VERSION);
    out.writeUnsigned(leaves.size());
    long previousGIndex = 0;
    for (final LeafEntry leaf : leaves) {
      out.writeUnsigned(leaf.gIndex() - previousGIndex);
      previousGIndex = leaf.gIndex();
      out.writeUnsigned(leaf.data().size());
      out.writeBytes(leaf.data());
    }
    out.writeUnsigned(supernodeGroups.size());
    for (final Map.Entry<Integer, List<SupernodeEntry>> group : supernodeGroups.entrySet()) {
      out.writeUnsigned(group.getKey());
      out.writeUnsigned(group.getValue().size());
      previousGIndex = 0;
      for (final SupernodeEntry supernode : group.getValue()) {
        out.writeUnsigned(supernode.gIndex() - previousGIndex);
        previousGIndex = supernode.gIndex();
        out.writeUnsigned(supernode.depth());
        out.writeBytes(supernode.hash());
      }
    }
    out.writeUnsigned(patchGroups.size());
    for (final Map.Entry<Integer, List<PatchEntry>> group : patchGroups.entrySet()) {
      final int elementSize = group.getValue().getFirst().elementSize();
      out.writeUnsigned(group.getKey());
      out.writeUnsigned(elementSize);
      out.writeUnsigned(group.getValue().size());
      previousGIndex = 0;
      for (final PatchEntry patch : group.getValue()) {
        if (patch.elementSize() != elementSize) {
          throw new IllegalArgumentException(
              "Patches of field "
                  + group.getKey()
                  + " have different element sizes "
                  + elementSize
                  + " and "
                  + patch.elementSize());
        }
        out.writeUnsigned(patch.gIndex() - previousGIndex);
        previousGIndex = patch.gIndex();
        out.writeUnsigned(patch.depth());
        out.writeUnsigned(patch.elementCount());
        out.writeUnsigned(patch.elementIndices().size());
        int previousIndex = -1;
        for (int i = 0; i < patch.elementIndices().size(); i++) {
          final int index = patch.elementIndices().getInt(i);
          out.writeUnsigned(index - previousIndex - 1);
          previousIndex = index;
        }
        out.writeBytes(patch.elements());
      }
    }
    out.writeUnsigned(zeroed.size());
    previousGIndex = 0;
    for (final ZeroedEntry entry : zeroed) {
      out.writeUnsigned(entry.gIndex() - previousGIndex);
      previousGIndex = entry.gIndex();
      out.writeBytes(entry.zeroRoot());
    }
    out.writeUnsigned(diff.copied().size());
    for (final CopiedEntry entry : diff.copied()) {
      out.writeUnsigned(entry.targetGIndex());
      out.writeUnsigned(entry.sourceGIndex());
    }
    out.writeUnsigned(diff.deltas().size());
    long compressedDeltaBytes = 0;
    for (final DeltaEntry entry : diff.deltas()) {
      out.writeUnsigned(entry.fieldIndex());
      out.writeUnsigned(entry.encodedDeltas().length);
      compressedDeltaBytes += out.writeCompressed(entry.encodedDeltas());
    }
    return new Encoded(out.toBytes(), compressedDeltaBytes);
  }

  public static TreeDiff deserialize(final Bytes bytes) {
    final ByteBuffer in = openVersioned(bytes);
    final List<LeafEntry> leaves = readLeaves(in);
    final List<SupernodeEntry> supernodes = readSupernodes(in);
    final List<PatchEntry> patches = readPatches(in);
    final List<ZeroedEntry> zeroed = readZeroed(in);
    final List<CopiedEntry> copied = readCopied(in);
    final List<DeltaEntry> deltas = readDeltas(in);
    checkNoTrailingBytes(in);
    return new TreeDiff(leaves, supernodes, patches, zeroed, copied, deltas);
  }

  /**
   * The hashes of the supernode entries of a serialized diff, in entry order, as {@code
   * deserialize(bytes).supernodes()} lists them. See {@link #supernodeEntries}.
   */
  public static List<Bytes32> supernodeHashes(final Bytes bytes) {
    final List<Bytes32> hashes = new ArrayList<>();
    for (final SupernodeEntry entry : supernodeEntries(bytes)) {
      hashes.add(entry.hash());
    }
    return hashes;
  }

  /**
   * The supernode entries of a serialized diff, in entry order, as {@code
   * deserialize(bytes).supernodes()} lists them. The other sections are walked without
   * materialising leaf data or decompressing delta entries (patches are read, their elements as
   * views, since they are few and small), so every input {@link #deserialize} rejects for its
   * structure (version, counts, lengths, truncation, trailing bytes, a delta entry's raw length
   * disagreeing with its zstd frame header) is rejected too; a corrupt compressed body inside a
   * well-framed delta entry is detected by {@link #deserialize} only.
   */
  public static List<SupernodeEntry> supernodeEntries(final Bytes bytes) {
    final ByteBuffer in = openVersioned(bytes);
    skipLeaves(in);
    final List<SupernodeEntry> supernodes = readSupernodes(in);
    readPatches(in);
    skipZeroed(in);
    readCopied(in);
    skipDeltas(in);
    checkNoTrailingBytes(in);
    return supernodes;
  }

  /**
   * An upper bound of the encoded size of {@code diff}: every varint at its maximum length and
   * every delta entry at zstd's worst-case compressed length.
   */
  private static int maxEncodedSize(final TreeDiff diff, final int groupCount) {
    final int gIndexBytes = VarInts.MAX_VARINT_BYTES;
    final int intBytes = MAX_INT_VARINT_BYTES;
    long size = 1 + 6L * intBytes;
    for (final LeafEntry leaf : diff.leaves()) {
      size += gIndexBytes + intBytes + leaf.data().size();
    }
    size += (long) groupCount * 3 * intBytes;
    size += (long) diff.supernodes().size() * (gIndexBytes + intBytes + Bytes32.SIZE);
    for (final PatchEntry patch : diff.patches()) {
      size +=
          gIndexBytes
              + 3L * intBytes
              + (long) patch.elementIndices().size() * intBytes
              + patch.elements().size();
    }
    size += (long) diff.zeroed().size() * (gIndexBytes + Bytes32.SIZE);
    size += (long) diff.copied().size() * 2 * gIndexBytes;
    for (final DeltaEntry entry : diff.deltas()) {
      size += 3L * intBytes + COMPRESSOR.maxCompressedLength(entry.encodedDeltas().length);
    }
    if (size > MAX_ARRAY_LENGTH) {
      throw new IllegalArgumentException("Tree diff too large to encode: up to " + size + " bytes");
    }
    return (int) size;
  }

  private static <T> Map<Integer, List<T>> groupByField(
      final List<T> entries, final ToIntFunction<T> fieldIndex, final Comparator<T> byGIndex) {
    final Map<Integer, List<T>> groups = new TreeMap<>();
    for (final T entry : entries) {
      groups.computeIfAbsent(fieldIndex.applyAsInt(entry), __ -> new ArrayList<>()).add(entry);
    }
    groups.values().forEach(group -> group.sort(byGIndex));
    return groups;
  }

  /**
   * Opens a payload for reading. The bytes are read in place when {@code bytes} wraps a whole
   * array; leaf data is returned as views of them.
   */
  private static ByteBuffer openVersioned(final Bytes bytes) {
    final ByteBuffer in = ByteBuffer.wrap(bytes.toArrayUnsafe());
    if (!in.hasRemaining()) {
      throw new IllegalArgumentException("Empty tree diff");
    }
    final int version = Byte.toUnsignedInt(in.get());
    if (version == 1) {
      throw new IllegalArgumentException(VERSION_ONE_MESSAGE);
    }
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

  private static List<LeafEntry> readLeaves(final ByteBuffer in) {
    final int count = readCount(in);
    final List<LeafEntry> leaves = new ArrayList<>(count);
    long gIndex = 0;
    for (int i = 0; i < count; i++) {
      gIndex += VarInts.readUnsigned(in);
      final int length = readInt(in);
      checkAvailable(in, length);
      // A view: tree leaf nodes copy their data
      leaves.add(
          new LeafEntry(gIndex, Bytes.wrap(in.array(), in.arrayOffset() + in.position(), length)));
      in.position(in.position() + length);
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

  /**
   * Reads the supernode section. Hashes are copied rather than viewed, since callers keep them in
   * long-lived maps that would otherwise pin whole payloads.
   */
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

  /**
   * Reads the patch section. Written elements are views of the payload: the applier copies them
   * into the supernodes it builds.
   */
  private static List<PatchEntry> readPatches(final ByteBuffer in) {
    final int groupCount = readCount(in);
    final List<PatchEntry> patches = new ArrayList<>();
    for (int group = 0; group < groupCount; group++) {
      final int fieldIndex = readInt(in);
      final int elementSize = readInt(in);
      final int count = readCount(in);
      long gIndex = 0;
      for (int i = 0; i < count; i++) {
        gIndex += VarInts.readUnsigned(in);
        final int depth = readInt(in);
        final int elementCount = readInt(in);
        final int writtenCount = readCount(in);
        final IntList elementIndices = new IntArrayList(writtenCount);
        long index = -1;
        for (int written = 0; written < writtenCount; written++) {
          index += 1L + readInt(in);
          if (index > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Patch element index out of int range at gIndex " + gIndex);
          }
          elementIndices.add((int) index);
        }
        final long elementBytes = (long) writtenCount * elementSize;
        if (elementBytes > in.remaining()) {
          throw new IllegalArgumentException(
              "Truncated tree diff: need " + elementBytes + " bytes, have " + in.remaining());
        }
        // A view; tuweni rejects an empty view at the end of the array
        final Bytes elements =
            elementBytes == 0
                ? Bytes.EMPTY
                : Bytes.wrap(in.array(), in.arrayOffset() + in.position(), (int) elementBytes);
        in.position(in.position() + (int) elementBytes);
        patches.add(
            new PatchEntry(
                fieldIndex, gIndex, depth, elementCount, elementSize, elementIndices, elements));
      }
    }
    return patches;
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

  /** Reads the delta section, decompressing each entry straight from the payload. */
  private static List<DeltaEntry> readDeltas(final ByteBuffer in) {
    final int count = readCount(in);
    final List<DeltaEntry> deltas = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      final int fieldIndex = readInt(in);
      final int rawLength = readInt(in);
      final int compressedLength = readInt(in);
      checkCompressedFrame(in, fieldIndex, rawLength, compressedLength);
      final byte[] raw = new byte[rawLength];
      final int decompressedLength;
      try {
        decompressedLength =
            new ZstdJavaDecompressor()
                .decompress(
                    in.array(),
                    in.arrayOffset() + in.position(),
                    compressedLength,
                    raw,
                    0,
                    rawLength);
      } catch (final RuntimeException e) {
        // Malformed input surfaces as MalformedInputException or as a bounds failure
        throw new IllegalArgumentException(
            "Corrupt compressed deltas of field " + fieldIndex + ": " + e.getMessage(), e);
      }
      if (decompressedLength != rawLength) {
        throw new IllegalArgumentException(
            "Compressed deltas of field "
                + fieldIndex
                + " decompress to "
                + decompressedLength
                + " bytes, not the "
                + rawLength
                + " recorded");
      }
      in.position(in.position() + compressedLength);
      deltas.add(new DeltaEntry(fieldIndex, raw));
    }
    return deltas;
  }

  private static void skipDeltas(final ByteBuffer in) {
    final int count = readCount(in);
    for (int i = 0; i < count; i++) {
      final int fieldIndex = readInt(in);
      final int rawLength = readInt(in);
      final int compressedLength = readInt(in);
      checkCompressedFrame(in, fieldIndex, rawLength, compressedLength);
      in.position(in.position() + compressedLength);
    }
  }

  /**
   * Checks that the next {@code compressedLength} bytes are available and are a zstd frame whose
   * header declares {@code rawLength} bytes, which the frame's size also bounds, before anything is
   * allocated for it. Reads the frame header only.
   */
  private static void checkCompressedFrame(
      final ByteBuffer in, final int fieldIndex, final int rawLength, final int compressedLength) {
    checkAvailable(in, compressedLength);
    if (rawLength > MAX_DECOMPRESSION_RATIO * compressedLength) {
      throw new IllegalArgumentException(
          "Deltas of field "
              + fieldIndex
              + " record "
              + rawLength
              + " raw bytes, more than "
              + compressedLength
              + " compressed bytes can hold");
    }
    final long declaredLength;
    try {
      declaredLength =
          new ZstdJavaDecompressor()
              .getDecompressedSize(in.array(), in.arrayOffset() + in.position(), compressedLength);
    } catch (final RuntimeException e) {
      throw new IllegalArgumentException(
          "Corrupt compressed deltas of field " + fieldIndex + ": " + e.getMessage(), e);
    }
    if (declaredLength != rawLength) {
      throw new IllegalArgumentException(
          "Deltas of field "
              + fieldIndex
              + " record "
              + rawLength
              + " raw bytes but their zstd frame declares "
              + declaredLength);
    }
  }

  private static <T> List<T> sorted(final List<T> entries, final Comparator<T> comparator) {
    final List<T> copy = new ArrayList<>(entries);
    copy.sort(comparator);
    return copy;
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

  /** Writes into an array sized by {@link #maxEncodedSize}, so it never grows. */
  private static final class Writer {

    private final byte[] buffer;
    private final MutableBytes view;
    private int position;

    private Writer(final int capacity) {
      this.buffer = new byte[capacity];
      this.view = MutableBytes.wrap(buffer);
    }

    void writeByte(final int value) {
      buffer[position++] = (byte) value;
    }

    void writeUnsigned(final long value) {
      position = VarInts.writeUnsigned(buffer, position, value);
    }

    void writeBytes(final Bytes data) {
      data.copyTo(view, position);
      position += data.size();
    }

    /**
     * Writes {@code raw} as a zstd frame prefixed by its length. The frame is compressed into the
     * buffer past room for the longest prefix, then moved down next to the prefix written.
     *
     * @return the compressed length
     */
    int writeCompressed(final byte[] raw) {
      final int frameStart = position + MAX_INT_VARINT_BYTES;
      final int length =
          COMPRESSOR.compress(raw, 0, raw.length, buffer, frameStart, buffer.length - frameStart);
      writeUnsigned(length);
      System.arraycopy(buffer, frameStart, buffer, position, length);
      position += length;
      return length;
    }

    Bytes toBytes() {
      return Bytes.wrap(position == buffer.length ? buffer : Arrays.copyOf(buffer, position));
    }
  }
}
