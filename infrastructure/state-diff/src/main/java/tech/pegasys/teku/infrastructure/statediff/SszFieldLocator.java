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
 * Locates variable-length fields within serialized SSZ beacon state bytes by parsing the SSZ offset
 * table.
 */
public interface SszFieldLocator {

  /** Returns the byte regions of UInt64 list fields in the given SSZ bytes. */
  List<FieldRegion> locateUInt64Fields(Bytes ssz);

  /** Returns the byte regions of ALL variable-length fields in the given SSZ bytes. */
  List<VariableFieldRegion> locateAllVariableFields(Bytes ssz);

  record FieldRegion(int offset, int length) {}

  record VariableFieldRegion(int offset, int length, boolean isUInt64) {}

  /**
   * Field locator that uses known SSZ field indices to parse the offset table. The field indices
   * refer to variable-length fields in the SSZ container.
   */
  class IndexBasedFieldLocator implements SszFieldLocator {

    private final int[] variableFieldOffsetPositions;
    private final int[] uint64FieldVariableIndices;
    private final int totalVariableFields;

    /**
     * @param variableFieldOffsetPositions positions in the fixed part where each variable-length
     *     field's offset is stored (in order)
     * @param uint64FieldVariableIndices which variable-length fields (by index into
     *     variableFieldOffsetPositions) are UInt64 lists
     */
    public IndexBasedFieldLocator(
        final int[] variableFieldOffsetPositions, final int[] uint64FieldVariableIndices) {
      this.variableFieldOffsetPositions = variableFieldOffsetPositions;
      this.uint64FieldVariableIndices = uint64FieldVariableIndices;
      this.totalVariableFields = variableFieldOffsetPositions.length;
    }

    @Override
    public List<FieldRegion> locateUInt64Fields(final Bytes ssz) {
      final ByteBuffer buf = ByteBuffer.wrap(ssz.toArrayUnsafe()).order(ByteOrder.LITTLE_ENDIAN);
      final int sszSize = ssz.size();

      // Read all variable-field offsets from the fixed part
      final int[] offsets = new int[totalVariableFields];
      for (int i = 0; i < totalVariableFields; i++) {
        buf.position(variableFieldOffsetPositions[i]);
        offsets[i] = buf.getInt();
      }

      final List<FieldRegion> regions = new ArrayList<>(uint64FieldVariableIndices.length);
      for (final int idx : uint64FieldVariableIndices) {
        final int fieldOffset = offsets[idx];
        final int fieldEnd;
        if (idx + 1 < totalVariableFields) {
          fieldEnd = offsets[idx + 1];
        } else {
          fieldEnd = sszSize;
        }
        regions.add(new FieldRegion(fieldOffset, fieldEnd - fieldOffset));
      }

      return regions;
    }

    @Override
    public List<VariableFieldRegion> locateAllVariableFields(final Bytes ssz) {
      final ByteBuffer buf = ByteBuffer.wrap(ssz.toArrayUnsafe()).order(ByteOrder.LITTLE_ENDIAN);
      final int sszSize = ssz.size();

      final int[] offsets = new int[totalVariableFields];
      for (int i = 0; i < totalVariableFields; i++) {
        buf.position(variableFieldOffsetPositions[i]);
        offsets[i] = buf.getInt();
      }

      final List<VariableFieldRegion> regions = new ArrayList<>(totalVariableFields);
      for (int i = 0; i < totalVariableFields; i++) {
        final int fieldOffset = offsets[i];
        final int fieldEnd = (i + 1 < totalVariableFields) ? offsets[i + 1] : sszSize;
        regions.add(new VariableFieldRegion(fieldOffset, fieldEnd - fieldOffset, isUInt64Field(i)));
      }
      return regions;
    }

    private boolean isUInt64Field(final int variableIndex) {
      for (final int idx : uint64FieldVariableIndices) {
        if (idx == variableIndex) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * A field locator that returns pre-defined fixed regions. Used during deserialization when
   * regions are already known from the serialized diff.
   */
  class FixedFieldLocator implements SszFieldLocator {
    private final List<FieldRegion> regions;

    public FixedFieldLocator(final List<FieldRegion> regions) {
      this.regions = regions;
    }

    @Override
    public List<FieldRegion> locateUInt64Fields(final Bytes ssz) {
      return regions;
    }

    @Override
    public List<VariableFieldRegion> locateAllVariableFields(final Bytes ssz) {
      throw new UnsupportedOperationException(
          "FixedFieldLocator does not support locateAllVariableFields");
    }
  }
}
