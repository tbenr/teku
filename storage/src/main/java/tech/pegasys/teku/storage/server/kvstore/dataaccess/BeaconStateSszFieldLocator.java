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

package tech.pegasys.teku.storage.server.kvstore.dataaccess;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.infrastructure.statediff.SszFieldLocator;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconStateSchema;

/**
 * Locates BALANCES and INACTIVITY_SCORES fields in serialized BeaconState SSZ by computing the
 * variable field offset positions from the state schema, then delegating to {@link
 * SszFieldLocator.IndexBasedFieldLocator}.
 */
public class BeaconStateSszFieldLocator implements SszFieldLocator {

  private static final String BALANCES_FIELD_NAME = "balances";
  private static final String INACTIVITY_SCORES_FIELD_NAME = "inactivity_scores";

  private final IndexBasedFieldLocator delegate;

  private BeaconStateSszFieldLocator(final IndexBasedFieldLocator delegate) {
    this.delegate = delegate;
  }

  public static BeaconStateSszFieldLocator create(final Spec spec) {
    return createFromSchema(spec.getGenesisSchemaDefinitions().getBeaconStateSchema());
  }

  public static BeaconStateSszFieldLocator createFromSchema(
      final BeaconStateSchema<? extends BeaconState, ?> schema) {
    final List<? extends SszSchema<?>> fields = schema.getFieldSchemas();
    final List<String> fieldNames = schema.getFieldNames();

    final List<Integer> variableOffsetPositions = new ArrayList<>();
    final List<Integer> uint64VarIndices = new ArrayList<>();
    int bytePosition = 0;
    int variableIndex = 0;

    for (int i = 0; i < fields.size(); i++) {
      final SszSchema<?> fieldSchema = fields.get(i);
      final String fieldName = fieldNames.get(i);
      final boolean isVariable = !fieldSchema.isFixedSize();

      if (isVariable) {
        variableOffsetPositions.add(bytePosition);
        if (fieldName.equals(BALANCES_FIELD_NAME)
            || fieldName.equals(INACTIVITY_SCORES_FIELD_NAME)) {
          uint64VarIndices.add(variableIndex);
        }
        variableIndex++;
        bytePosition += 4; // SSZ offset pointer size
      } else {
        bytePosition += fieldSchema.getSszFixedPartSize();
      }
    }

    return new BeaconStateSszFieldLocator(
        new IndexBasedFieldLocator(
            variableOffsetPositions.stream().mapToInt(Integer::intValue).toArray(),
            uint64VarIndices.stream().mapToInt(Integer::intValue).toArray()));
  }

  @Override
  public List<FieldRegion> locateUInt64Fields(final Bytes ssz) {
    return delegate.locateUInt64Fields(ssz);
  }

  public static Set<UInt64> getForkEpochs(final Spec spec) {
    final Set<UInt64> forkEpochs = new HashSet<>();
    for (final var fork : spec.getForkSchedule().getForks()) {
      final UInt64 epoch = fork.getEpoch();
      if (!epoch.equals(UInt64.ZERO)) {
        forkEpochs.add(epoch);
      }
    }
    return forkEpochs;
  }
}
