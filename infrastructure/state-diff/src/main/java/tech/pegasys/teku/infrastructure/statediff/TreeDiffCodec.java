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
import java.util.stream.Collectors;
import org.apache.tuweni.bytes.AbstractBytes;
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
 * grouped the same way; each entry stores the gIndex delta, the depth, the element size, the
 * element count, the number of written elements, each written element's index as the gap from the
 * previous one plus one (the first from -1), then the written elements' bytes back to back. Copied
 * entries store both gIndexes and leaf data is length-prefixed. Each delta entry stores its field
 * index, the length of its {@link UInt64DeltaCodec} bytes, then those bytes compressed as one zstd
 * frame, prefixed by the compressed length.
 */
public final class TreeDiffCodec {

  static final int VERSION = 2;

  /**
   * zstd stores at least 4 bytes for a block of at most 128 KiB, so a frame decompresses to at most
   * this many times its size; a raw length beyond that is corrupt and is not allocated.
   */
  private static final long MAX_DECOMPRESSION_RATIO = 32 * 1024;

  /** The most bytes a varint of an int value takes. */
  private static final int MAX_INT_VARINT_BYTES = VarInts.unsignedLength(Integer.MAX_VALUE);

  /** Stateless and level 3; the Java implementation, so payloads do not depend on a native one. */
  private static final ZstdJavaCompressor COMPRESSOR = new ZstdJavaCompressor();

  /**
   * Reads zstd frame headers only: {@link ZstdJavaDecompressor#getDecompressedSize} delegates to a
   * static method and leaves the instance's scratch space alone, so one instance can be shared.
   * Decompressing takes a decompressor of its own.
   */
  private static final ZstdJavaDecompressor FRAME_HEADER_READER = new ZstdJavaDecompressor();

  private TreeDiffCodec() {}

  /**
   * An encoded diff.
   *
   * @param bytes the reserved prefix, zeroed for the caller to fill, then the payload
   * @param prefixLength the length of the reserved prefix
   * @param sectionBytes the bytes each section of the payload takes
   */
  public record Encoded(MutableBytes bytes, int prefixLength, SectionBytes sectionBytes) {

    /** The payload, a {@link #payloadView} of {@link #bytes}. */
    public Bytes payload() {
      return payloadView(bytes.toArrayUnsafe(), prefixLength, bytes.size() - prefixLength);
    }
  }

  /**
   * The bytes each section takes in a payload, its count, group headers, gIndexes and lengths
   * included, so that they sum to the payload size less its version byte. The delta section holds
   * the compressed deltas.
   */
  public record SectionBytes(
      int leaves, int supernodes, int patches, int zeroed, int copied, int deltas) {}

  /**
   * The encoded bytes of a patch entry of {@code elementCount} elements of {@code elementSize}
   * bytes besides its gIndex delta, its depth and its written elements, its written count bounded
   * by the element count.
   */
  static int patchEntryBytes(final int elementSize, final int elementCount) {
    return VarInts.unsignedLength(elementSize) + 2 * VarInts.unsignedLength(elementCount);
  }

  /**
   * The encoded bytes of a written element of {@code elementSize} bytes following the previous one
   * by {@code gap} unwritten elements in a patch entry.
   */
  static int patchElementBytes(final int gap, final int elementSize) {
    return VarInts.unsignedLength(gap) + elementSize;
  }

  public static Bytes serialize(final TreeDiff diff) {
    return encode(diff, 0).payload();
  }

  /**
   * Encodes {@code diff} into one buffer sized for the worst case, after {@code prefixLength}
   * reserved bytes, compressing each delta entry straight into it, and copies the result once to
   * its exact size. A caller storing the payload behind a header of its own writes the header into
   * the reserved bytes rather than copying the payload again.
   */
  public static Encoded encode(final TreeDiff diff, final int prefixLength) {
    final List<LeafEntry> leaves =
        diff.leaves().stream().sorted(Comparator.comparingLong(LeafEntry::gIndex)).toList();
    final Map<Integer, List<SupernodeEntry>> supernodeGroups =
        groupByField(
            diff.supernodes(),
            SupernodeEntry::fieldIndex,
            Comparator.comparingLong(SupernodeEntry::gIndex));
    final Map<Integer, List<PatchEntry>> patchGroups =
        groupByField(
            diff.patches(), PatchEntry::fieldIndex, Comparator.comparingLong(PatchEntry::gIndex));
    final List<ZeroedEntry> zeroed =
        diff.zeroed().stream().sorted(Comparator.comparingLong(ZeroedEntry::gIndex)).toList();

    final long capacity = prefixLength + maxEncodedSize(diff);
    if (capacity > VarInts.MAX_ARRAY_LENGTH) {
      throw new IllegalArgumentException(
          "Tree diff too large to encode: up to " + capacity + " bytes");
    }
    final Writer out = new Writer((int) capacity, prefixLength);
    out.writeByte(VERSION);
    final int leavesStart = out.position;
    out.writeUnsigned(leaves.size());
    long previousGIndex = 0;
    for (final LeafEntry leaf : leaves) {
      out.writeUnsigned(leaf.gIndex() - previousGIndex);
      previousGIndex = leaf.gIndex();
      out.writeUnsigned(leaf.data().size());
      out.writeBytes(leaf.data());
    }
    final int supernodesStart = out.position;
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
    final int patchesStart = out.position;
    out.writeUnsigned(patchGroups.size());
    for (final Map.Entry<Integer, List<PatchEntry>> group : patchGroups.entrySet()) {
      out.writeUnsigned(group.getKey());
      out.writeUnsigned(group.getValue().size());
      previousGIndex = 0;
      for (final PatchEntry patch : group.getValue()) {
        out.writeUnsigned(patch.gIndex() - previousGIndex);
        previousGIndex = patch.gIndex();
        out.writeUnsigned(patch.depth());
        out.writeUnsigned(patch.elementSize());
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
    final int zeroedStart = out.position;
    out.writeUnsigned(zeroed.size());
    previousGIndex = 0;
    for (final ZeroedEntry entry : zeroed) {
      out.writeUnsigned(entry.gIndex() - previousGIndex);
      previousGIndex = entry.gIndex();
      out.writeBytes(entry.zeroRoot());
    }
    final int copiedStart = out.position;
    out.writeUnsigned(diff.copied().size());
    for (final CopiedEntry entry : diff.copied()) {
      out.writeUnsigned(entry.targetGIndex());
      out.writeUnsigned(entry.sourceGIndex());
    }
    final int deltasStart = out.position;
    out.writeUnsigned(diff.deltas().size());
    for (final DeltaEntry entry : diff.deltas()) {
      out.writeUnsigned(entry.fieldIndex());
      out.writeUnsigned(entry.encodedDeltas().length);
      out.writeCompressed(entry.encodedDeltas());
    }
    return new Encoded(
        out.toBytes(),
        prefixLength,
        new SectionBytes(
            supernodesStart - leavesStart,
            patchesStart - supernodesStart,
            zeroedStart - patchesStart,
            copiedStart - zeroedStart,
            deltasStart - copiedStart,
            out.position - deltasStart));
  }

  public static TreeDiff deserialize(final Bytes bytes) {
    return parse(bytes, true);
  }

  /**
   * The hashes of the supernode entries of a serialized diff, in entry order, as {@code
   * deserialize(bytes).supernodes()} lists them. See {@link #supernodeEntries}.
   */
  public static List<Bytes32> supernodeHashes(final Bytes bytes) {
    return supernodeEntries(bytes).stream().map(SupernodeEntry::hash).toList();
  }

  /**
   * The supernode entries of a serialized diff, in entry order, as {@code
   * deserialize(bytes).supernodes()} lists them. The diff is read as {@link #deserialize} reads it
   * but without decompressing delta entries, so every input {@link #deserialize} rejects for its
   * structure (version, counts, lengths, truncation, trailing bytes, a delta entry's raw length
   * disagreeing with its zstd frame header) is rejected too; a corrupt compressed body inside a
   * well-framed delta entry is detected by {@link #deserialize} only.
   */
  public static List<SupernodeEntry> supernodeEntries(final Bytes bytes) {
    return parse(bytes, false).supernodes();
  }

  /**
   * Reads a payload. Without {@code decompressDeltas} the delta entries are checked against their
   * frame headers and skipped, and the result has none.
   */
  private static TreeDiff parse(final Bytes bytes, final boolean decompressDeltas) {
    final ByteBuffer in = openVersioned(bytes);
    final TreeDiff diff =
        new TreeDiff(
            readLeaves(in),
            readSupernodes(in),
            readPatches(in),
            readZeroed(in),
            readCopied(in),
            readDeltas(in, decompressDeltas));
    if (in.hasRemaining()) {
      throw new IllegalArgumentException(in.remaining() + " trailing bytes after tree diff");
    }
    return diff;
  }

  /**
   * An upper bound of the encoded size of {@code diff}: every varint at the maximum length of a
   * 64-bit value and every delta entry at zstd's worst-case compressed length.
   */
  private static long maxEncodedSize(final TreeDiff diff) {
    // The six section counts; per leaf its gIndex and length; per supernode its group's field
    // index and count (at most one group per entry), its gIndex and depth; per patch the same
    // plus its element size, count and written count and one per written index; per zeroed entry
    // its gIndex; per copied entry two gIndexes; per delta entry its field index and two lengths
    long varints =
        6
            + 2L * diff.leaves().size()
            + 4L * diff.supernodes().size()
            + diff.zeroed().size()
            + 2L * diff.copied().size()
            + 3L * diff.deltas().size();
    long data = 1 + (long) Bytes32.SIZE * (diff.supernodes().size() + diff.zeroed().size());
    for (final LeafEntry leaf : diff.leaves()) {
      data += leaf.data().size();
    }
    for (final PatchEntry patch : diff.patches()) {
      varints += 7 + patch.elementIndices().size();
      data += patch.elements().size();
    }
    for (final DeltaEntry entry : diff.deltas()) {
      data += COMPRESSOR.maxCompressedLength(entry.encodedDeltas().length);
    }
    return data + varints * VarInts.MAX_VARINT_BYTES;
  }

  private static <T> Map<Integer, List<T>> groupByField(
      final List<T> entries, final ToIntFunction<T> fieldIndex, final Comparator<T> byGIndex) {
    return entries.stream()
        .sorted(byGIndex)
        .collect(Collectors.groupingBy(fieldIndex::applyAsInt, TreeMap::new, Collectors.toList()));
  }

  /**
   * The payload stored in {@code array} from {@code offset} on for {@code length} bytes, as a view
   * that {@link #deserialize} and {@link #supernodeEntries} read in place: a payload read from a
   * larger record is then not copied out of it. The array must not change while the view is in use.
   */
  public static Bytes payloadView(final byte[] array, final int offset, final int length) {
    return new PayloadView(array, offset, length);
  }

  /**
   * Opens a payload for reading. The bytes are read in place when {@code bytes} is a {@link
   * #payloadView} or wraps a whole array; leaf data and patch elements are returned as views of
   * them.
   */
  private static ByteBuffer openVersioned(final Bytes bytes) {
    final ByteBuffer in =
        bytes instanceof PayloadView view ? view.buffer() : ByteBuffer.wrap(bytes.toArrayUnsafe());
    if (!in.hasRemaining()) {
      throw new IllegalArgumentException("Empty tree diff");
    }
    final int version = Byte.toUnsignedInt(in.get());
    if (version != VERSION) {
      throw new IllegalArgumentException("Unknown tree diff version " + version);
    }
    return in;
  }

  private static List<LeafEntry> readLeaves(final ByteBuffer in) {
    final int count = readCount(in);
    final List<LeafEntry> leaves = new ArrayList<>(count);
    long gIndex = 0;
    for (int i = 0; i < count; i++) {
      gIndex += VarInts.readUnsigned(in);
      // A view: tree leaf nodes copy their data
      leaves.add(new LeafEntry(gIndex, view(in, readInt(in))));
    }
    return leaves;
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
      final int count = readCount(in);
      long gIndex = 0;
      for (int i = 0; i < count; i++) {
        gIndex += VarInts.readUnsigned(in);
        final int depth = readInt(in);
        final int elementSize = readInt(in);
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
        final Bytes elements = view(in, (long) writtenCount * elementSize);
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

  /**
   * Reads the delta section, decompressing each entry straight from the payload, or with {@code
   * decompress} false checking each entry's frame header only and returning none.
   */
  private static List<DeltaEntry> readDeltas(final ByteBuffer in, final boolean decompress) {
    final int count = readCount(in);
    final List<DeltaEntry> deltas = new ArrayList<>(decompress ? count : 0);
    // Its scratch space takes ~150 KB, so one serves every entry
    final ZstdJavaDecompressor decompressor =
        decompress && count > 0 ? new ZstdJavaDecompressor() : null;
    for (int i = 0; i < count; i++) {
      final int fieldIndex = readInt(in);
      final int rawLength = readInt(in);
      final int compressedLength = readInt(in);
      checkCompressedFrame(in, fieldIndex, rawLength, compressedLength);
      if (decompressor == null) {
        in.position(in.position() + compressedLength);
        continue;
      }
      final byte[] raw = new byte[rawLength];
      final int decompressedLength;
      try {
        decompressedLength =
            decompressor.decompress(
                in.array(), in.arrayOffset() + in.position(), compressedLength, raw, 0, rawLength);
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
          FRAME_HEADER_READER.getDecompressedSize(
              in.array(), in.arrayOffset() + in.position(), compressedLength);
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

  private static byte[] readBytes(final ByteBuffer in, final int length) {
    checkAvailable(in, length);
    final byte[] data = new byte[length];
    in.get(data);
    return data;
  }

  /** A view of the next {@code length} bytes, which are skipped. */
  private static Bytes view(final ByteBuffer in, final long length) {
    checkAvailable(in, length);
    // tuweni rejects an empty view at the end of the array
    final Bytes view =
        length == 0
            ? Bytes.EMPTY
            : Bytes.wrap(in.array(), in.arrayOffset() + in.position(), (int) length);
    in.position(in.position() + (int) length);
    return view;
  }

  private static void checkAvailable(final ByteBuffer in, final long length) {
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

  /** A view of part of an array that keeps the array and offset for {@link #openVersioned}. */
  private static final class PayloadView extends AbstractBytes {
    private final byte[] array;
    private final int offset;
    private final Bytes delegate;

    private PayloadView(final byte[] array, final int offset, final int length) {
      this.array = array;
      this.offset = offset;
      // tuweni rejects an empty view at the end of the array
      this.delegate = length == 0 ? Bytes.EMPTY : Bytes.wrap(array, offset, length);
    }

    /** The viewed bytes as a buffer whose position is 0 and whose array is the viewed one. */
    private ByteBuffer buffer() {
      return ByteBuffer.wrap(array, offset, delegate.size()).slice();
    }

    @Override
    public int size() {
      return delegate.size();
    }

    @Override
    public byte get(final int i) {
      return delegate.get(i);
    }

    @Override
    public Bytes slice(final int i, final int length) {
      return delegate.slice(i, length);
    }

    @Override
    public Bytes copy() {
      return delegate.copy();
    }

    @Override
    public MutableBytes mutableCopy() {
      return delegate.mutableCopy();
    }

    @Override
    public void copyTo(final MutableBytes destination, final int destinationOffset) {
      delegate.copyTo(destination, destinationOffset);
    }

    @Override
    public byte[] toArrayUnsafe() {
      return delegate.toArrayUnsafe();
    }
  }

  /** Writes into an array sized by {@link #maxEncodedSize}, so it never grows. */
  private static final class Writer {

    private final byte[] buffer;
    private final MutableBytes view;
    private int position;

    private Writer(final int capacity, final int start) {
      this.buffer = new byte[capacity];
      this.view = MutableBytes.wrap(buffer);
      this.position = start;
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
     */
    void writeCompressed(final byte[] raw) {
      final int frameStart = position + MAX_INT_VARINT_BYTES;
      final int length =
          COMPRESSOR.compress(raw, 0, raw.length, buffer, frameStart, buffer.length - frameStart);
      writeUnsigned(length);
      System.arraycopy(buffer, frameStart, buffer, position, length);
      position += length;
    }

    MutableBytes toBytes() {
      return MutableBytes.wrap(
          position == buffer.length ? buffer : Arrays.copyOf(buffer, position));
    }
  }
}
