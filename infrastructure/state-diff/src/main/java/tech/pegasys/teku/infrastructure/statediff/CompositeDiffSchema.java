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
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;

/**
 * Composite diff strategy: applies UInt64DeltaDiff to specified byte regions (balances,
 * inactivity_scores) and SimpleSszDiff to the remainder of the state.
 */
public class CompositeDiffSchema implements StateDiffSchema {

  private static final byte FORMAT_VERSION = 1;
  static final Bytes COMPOSITE_MAGIC = Bytes.of(0x43, 0x44, 0x49, 0x46); // "CDIF"

  private final SszFieldLocator fieldLocator;
  private final SimpleSszDiff.Schema simpleDiffSchema;
  private final UInt64DeltaDiff.Schema uint64DiffSchema;

  public CompositeDiffSchema(final SszFieldLocator fieldLocator) {
    this.fieldLocator = fieldLocator;
    this.simpleDiffSchema = new SimpleSszDiff.Schema();
    this.uint64DiffSchema = new UInt64DeltaDiff.Schema();
  }

  @Override
  public StateDiff computeDiff(final Bytes baseSsz, final Bytes targetSsz) {
    final List<SszFieldLocator.FieldRegion> baseRegions = fieldLocator.locateUInt64Fields(baseSsz);
    final List<SszFieldLocator.FieldRegion> targetRegions =
        fieldLocator.locateUInt64Fields(targetSsz);

    // Build "rest" SSZ by zeroing out the UInt64 regions, then diff the rest
    final Bytes baseRest = zeroOutRegions(baseSsz, baseRegions);
    final Bytes targetRest = zeroOutRegions(targetSsz, targetRegions);
    final StateDiff restDiff = simpleDiffSchema.computeDiff(baseRest, targetRest);

    // Diff each UInt64 region
    final StateDiff[] uint64Diffs = new StateDiff[baseRegions.size()];
    for (int i = 0; i < baseRegions.size(); i++) {
      final SszFieldLocator.FieldRegion baseRegion = baseRegions.get(i);
      final SszFieldLocator.FieldRegion targetRegion = targetRegions.get(i);
      final Bytes baseField = baseSsz.slice(baseRegion.offset(), baseRegion.length());
      final Bytes targetField = targetSsz.slice(targetRegion.offset(), targetRegion.length());
      uint64Diffs[i] = uint64DiffSchema.computeDiff(baseField, targetField);
    }

    return new CompositeDiff(restDiff, uint64Diffs, baseRegions, targetRegions);
  }

  @Override
  public StateDiff deserialize(final Bytes serialized) {
    final ByteBuffer buf =
        ByteBuffer.wrap(serialized.toArrayUnsafe()).order(ByteOrder.LITTLE_ENDIAN);

    final byte[] magic = new byte[4];
    buf.get(magic);
    if (!Bytes.wrap(magic).equals(COMPOSITE_MAGIC)) {
      throw new IllegalArgumentException("Invalid composite diff magic");
    }

    final byte version = buf.get();
    if (version != FORMAT_VERSION) {
      throw new IllegalArgumentException("Unsupported CompositeDiff format version: " + version);
    }

    // Read rest diff
    final int restDiffLen = buf.getInt();
    final byte[] restDiffBytes = new byte[restDiffLen];
    buf.get(restDiffBytes);
    final StateDiff restDiff = simpleDiffSchema.deserialize(Bytes.wrap(restDiffBytes));

    // Read region count and diffs
    final int regionCount = buf.getInt();
    final StateDiff[] uint64Diffs = new StateDiff[regionCount];
    final SszFieldLocator.FieldRegion[] baseRegionsArr =
        new SszFieldLocator.FieldRegion[regionCount];
    final SszFieldLocator.FieldRegion[] targetRegionsArr =
        new SszFieldLocator.FieldRegion[regionCount];

    for (int i = 0; i < regionCount; i++) {
      final int baseOffset = buf.getInt();
      final int baseLength = buf.getInt();
      baseRegionsArr[i] = new SszFieldLocator.FieldRegion(baseOffset, baseLength);

      final int targetOffset = buf.getInt();
      final int targetLength = buf.getInt();
      targetRegionsArr[i] = new SszFieldLocator.FieldRegion(targetOffset, targetLength);

      final int diffLen = buf.getInt();
      final byte[] diffBytes = new byte[diffLen];
      buf.get(diffBytes);
      uint64Diffs[i] = uint64DiffSchema.deserialize(Bytes.wrap(diffBytes));
    }

    return new CompositeDiff(
        restDiff, uint64Diffs, List.of(baseRegionsArr), List.of(targetRegionsArr));
  }

  private static Bytes zeroOutRegions(
      final Bytes ssz, final List<SszFieldLocator.FieldRegion> regions) {
    final MutableBytes copy = ssz.mutableCopy();
    for (final SszFieldLocator.FieldRegion region : regions) {
      final int end = Math.min(region.offset() + region.length(), copy.size());
      for (int i = region.offset(); i < end; i++) {
        copy.set(i, (byte) 0);
      }
    }
    return copy;
  }

  private static class CompositeDiff implements StateDiff {

    private final StateDiff restDiff;
    private final StateDiff[] uint64Diffs;
    private final List<SszFieldLocator.FieldRegion> baseRegions;
    private final List<SszFieldLocator.FieldRegion> targetRegions;

    CompositeDiff(
        final StateDiff restDiff,
        final StateDiff[] uint64Diffs,
        final List<SszFieldLocator.FieldRegion> baseRegions,
        final List<SszFieldLocator.FieldRegion> targetRegions) {
      this.restDiff = restDiff;
      this.uint64Diffs = uint64Diffs;
      this.baseRegions = baseRegions;
      this.targetRegions = targetRegions;
    }

    @Override
    public Bytes apply(final Bytes baseSsz) {
      // Apply the rest diff (with zeroed-out regions) to get the skeleton
      final MutableBytes result = restDiff.apply(baseSsz).mutableCopy();

      // Apply each UInt64 region diff using base regions for input, target regions for output
      for (int i = 0; i < uint64Diffs.length; i++) {
        final SszFieldLocator.FieldRegion baseRegion = baseRegions.get(i);
        final SszFieldLocator.FieldRegion targetRegion = targetRegions.get(i);
        final Bytes baseField = baseSsz.slice(baseRegion.offset(), baseRegion.length());
        final Bytes reconstructed = uint64Diffs[i].apply(baseField);
        reconstructed.copyTo(result, targetRegion.offset());
      }

      return result;
    }

    @Override
    public Bytes serialize() {
      final Bytes restDiffSerialized = restDiff.serialize();

      // magic(4) + version(1) + restLen(4) + rest + regionCount(4)
      // Per region: baseOffset(4) + baseLen(4) + targetOffset(4) + targetLen(4) + diffLen(4) + diff
      int totalSize = 4 + 1 + 4 + restDiffSerialized.size() + 4;
      final Bytes[] uint64DiffsSerialized = new Bytes[uint64Diffs.length];
      for (int i = 0; i < uint64Diffs.length; i++) {
        uint64DiffsSerialized[i] = uint64Diffs[i].serialize();
        totalSize += 4 + 4 + 4 + 4 + 4 + uint64DiffsSerialized[i].size();
      }

      final ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
      buf.put(COMPOSITE_MAGIC.toArrayUnsafe());
      buf.put(FORMAT_VERSION);

      buf.putInt(restDiffSerialized.size());
      buf.put(restDiffSerialized.toArrayUnsafe());

      buf.putInt(uint64Diffs.length);
      for (int i = 0; i < uint64Diffs.length; i++) {
        final SszFieldLocator.FieldRegion base = baseRegions.get(i);
        buf.putInt(base.offset());
        buf.putInt(base.length());
        final SszFieldLocator.FieldRegion target = targetRegions.get(i);
        buf.putInt(target.offset());
        buf.putInt(target.length());
        buf.putInt(uint64DiffsSerialized[i].size());
        buf.put(uint64DiffsSerialized[i].toArrayUnsafe());
      }

      buf.flip();
      return Bytes.wrapByteBuffer(buf);
    }
  }
}
