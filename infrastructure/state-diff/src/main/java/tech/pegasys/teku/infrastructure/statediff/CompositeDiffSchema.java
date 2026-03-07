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

/**
 * Per-field diff strategy: extracts ALL variable-length fields individually from both states, diffs
 * each independently (UInt64DeltaDiff for uint64 lists, SimpleSszDiff for others), and diffs the
 * fixed part (always same size for same fork) with SimpleSszDiff. This eliminates cross-field
 * misalignment when earlier variable fields grow.
 */
public class CompositeDiffSchema implements StateDiffSchema {

  private static final byte FORMAT_VERSION = 3;
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
    final List<SszFieldLocator.VariableFieldRegion> baseFields =
        fieldLocator.locateAllVariableFields(baseSsz);
    final List<SszFieldLocator.VariableFieldRegion> targetFields =
        fieldLocator.locateAllVariableFields(targetSsz);

    if (baseFields.size() != targetFields.size()) {
      throw new IllegalArgumentException(
          "Base and target must have the same number of variable fields: "
              + baseFields.size()
              + " vs "
              + targetFields.size());
    }

    final int fieldCount = baseFields.size();

    // Fixed part: everything before the first variable field
    final int baseFixedSize = fieldCount > 0 ? baseFields.get(0).offset() : baseSsz.size();
    final int targetFixedSize = fieldCount > 0 ? targetFields.get(0).offset() : targetSsz.size();
    final StateDiff fixedPartDiff =
        simpleDiffSchema.computeDiff(
            baseSsz.slice(0, baseFixedSize), targetSsz.slice(0, targetFixedSize));

    // Diff each variable field independently
    final StateDiff[] fieldDiffs = new StateDiff[fieldCount];
    for (int i = 0; i < fieldCount; i++) {
      final SszFieldLocator.VariableFieldRegion bf = baseFields.get(i);
      final SszFieldLocator.VariableFieldRegion tf = targetFields.get(i);
      final Bytes baseField = baseSsz.slice(bf.offset(), bf.length());
      final Bytes targetField = targetSsz.slice(tf.offset(), tf.length());
      if (bf.isUInt64()) {
        fieldDiffs[i] = uint64DiffSchema.computeDiff(baseField, targetField);
      } else {
        fieldDiffs[i] = simpleDiffSchema.computeDiff(baseField, targetField);
      }
    }

    return new CompositeDiff(fixedPartDiff, fieldDiffs, baseFields, targetFields);
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

    // Read fixed part diff
    final int fixedPartDiffLen = buf.getInt();
    final byte[] fixedPartDiffBytes = new byte[fixedPartDiffLen];
    buf.get(fixedPartDiffBytes);
    final StateDiff fixedPartDiff = simpleDiffSchema.deserialize(Bytes.wrap(fixedPartDiffBytes));

    // Read field count and per-field diffs
    final int fieldCount = buf.getInt();
    final StateDiff[] fieldDiffs = new StateDiff[fieldCount];
    final List<SszFieldLocator.VariableFieldRegion> baseFields = new ArrayList<>(fieldCount);
    final List<SszFieldLocator.VariableFieldRegion> targetFields = new ArrayList<>(fieldCount);

    for (int i = 0; i < fieldCount; i++) {
      final int baseOffset = buf.getInt();
      final int baseLength = buf.getInt();
      final int targetOffset = buf.getInt();
      final int targetLength = buf.getInt();
      final boolean isUInt64 = buf.get() != 0;

      baseFields.add(new SszFieldLocator.VariableFieldRegion(baseOffset, baseLength, isUInt64));
      targetFields.add(
          new SszFieldLocator.VariableFieldRegion(targetOffset, targetLength, isUInt64));

      final int diffLen = buf.getInt();
      final byte[] diffBytes = new byte[diffLen];
      buf.get(diffBytes);
      if (isUInt64) {
        fieldDiffs[i] = uint64DiffSchema.deserialize(Bytes.wrap(diffBytes));
      } else {
        fieldDiffs[i] = simpleDiffSchema.deserialize(Bytes.wrap(diffBytes));
      }
    }

    return new CompositeDiff(fixedPartDiff, fieldDiffs, baseFields, targetFields);
  }

  private static class CompositeDiff implements StateDiff {

    private final StateDiff fixedPartDiff;
    private final StateDiff[] fieldDiffs;
    private final List<SszFieldLocator.VariableFieldRegion> baseFields;
    private final List<SszFieldLocator.VariableFieldRegion> targetFields;

    CompositeDiff(
        final StateDiff fixedPartDiff,
        final StateDiff[] fieldDiffs,
        final List<SszFieldLocator.VariableFieldRegion> baseFields,
        final List<SszFieldLocator.VariableFieldRegion> targetFields) {
      this.fixedPartDiff = fixedPartDiff;
      this.fieldDiffs = fieldDiffs;
      this.baseFields = baseFields;
      this.targetFields = targetFields;
    }

    @Override
    public Bytes apply(final Bytes baseSsz) {
      // Extract and apply fixed part diff
      final int baseFixedPartSize =
          baseFields.isEmpty() ? baseSsz.size() : baseFields.get(0).offset();
      final Bytes baseFixedPart = baseSsz.slice(0, baseFixedPartSize);
      final Bytes targetFixedPart = fixedPartDiff.apply(baseFixedPart);

      // Reconstruct each variable field from its diff
      int totalSize = targetFixedPart.size();
      final Bytes[] reconstructedFields = new Bytes[fieldDiffs.length];
      for (int i = 0; i < fieldDiffs.length; i++) {
        final SszFieldLocator.VariableFieldRegion bf = baseFields.get(i);
        final Bytes baseField = baseSsz.slice(bf.offset(), bf.length());
        reconstructedFields[i] = fieldDiffs[i].apply(baseField);
        totalSize += reconstructedFields[i].size();
      }

      // Concatenate: targetFixedPart + field_0 + field_1 + ...
      final byte[] result = new byte[totalSize];
      int pos = 0;
      System.arraycopy(targetFixedPart.toArrayUnsafe(), 0, result, pos, targetFixedPart.size());
      pos += targetFixedPart.size();
      for (final Bytes field : reconstructedFields) {
        System.arraycopy(field.toArrayUnsafe(), 0, result, pos, field.size());
        pos += field.size();
      }
      return Bytes.wrap(result);
    }

    @Override
    public Bytes serialize() {
      final Bytes fixedPartDiffSerialized = fixedPartDiff.serialize();

      // magic(4) + version(1) + fixedPartDiffLen(4) + fixedPartDiff + fieldCount(4)
      // Per field: baseOffset(4) + baseLen(4) + targetOffset(4) + targetLen(4) + isUInt64(1)
      //            + diffLen(4) + diff
      int totalSize = 4 + 1 + 4 + fixedPartDiffSerialized.size() + 4;
      final Bytes[] fieldDiffsSerialized = new Bytes[fieldDiffs.length];
      for (int i = 0; i < fieldDiffs.length; i++) {
        fieldDiffsSerialized[i] = fieldDiffs[i].serialize();
        totalSize += 4 + 4 + 4 + 4 + 1 + 4 + fieldDiffsSerialized[i].size();
      }

      final ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
      buf.put(COMPOSITE_MAGIC.toArrayUnsafe());
      buf.put(FORMAT_VERSION);

      buf.putInt(fixedPartDiffSerialized.size());
      buf.put(fixedPartDiffSerialized.toArrayUnsafe());

      buf.putInt(fieldDiffs.length);
      for (int i = 0; i < fieldDiffs.length; i++) {
        final SszFieldLocator.VariableFieldRegion base = baseFields.get(i);
        buf.putInt(base.offset());
        buf.putInt(base.length());
        final SszFieldLocator.VariableFieldRegion target = targetFields.get(i);
        buf.putInt(target.offset());
        buf.putInt(target.length());
        buf.put((byte) (base.isUInt64() ? 1 : 0));
        buf.putInt(fieldDiffsSerialized[i].size());
        buf.put(fieldDiffsSerialized[i].toArrayUnsafe());
      }

      buf.flip();
      return Bytes.wrapByteBuffer(buf);
    }
  }
}
