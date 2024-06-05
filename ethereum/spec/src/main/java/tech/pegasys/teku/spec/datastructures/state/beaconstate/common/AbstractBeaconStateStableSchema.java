/*
 * Copyright Consensys Software Inc., 2022
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

package tech.pegasys.teku.spec.datastructures.state.beaconstate.common;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import tech.pegasys.teku.infrastructure.ssz.schema.impl.AbstractSszStableContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.sos.SszField;
import tech.pegasys.teku.spec.config.SpecConfig;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconStateStableSchema;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.MutableBeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.StableBeaconState;

public abstract class AbstractBeaconStateStableSchema<
        T extends StableBeaconState, TMutable extends MutableBeaconState>
    extends AbstractSszStableContainerSchema<T> implements BeaconStateStableSchema<T, TMutable> {
  protected AbstractBeaconStateStableSchema(final String name, final List<SszField> allFields, final int maxFieldCount) {
    super(
        name,
        continuousActiveNamedSchemas(
            allFields.stream().map(f -> namedSchema(f.getName(), f.getSchema().get())).toList()),
            maxFieldCount);
    validateFields(allFields);
  }

  protected AbstractBeaconStateStableSchema(
      final String name, final List<SszField> uniqueFields, final SpecConfig specConfig, final int maxFieldCount) {
    this(name, combineFields(BeaconStateFields.getCommonFields(specConfig), uniqueFields), maxFieldCount);
  }

  private static List<SszField> combineFields(
      final List<SszField> fieldsA, final List<SszField> fieldsB) {
    return Stream.concat(fieldsA.stream(), fieldsB.stream())
        .sorted(Comparator.comparing(SszField::getIndex))
        .toList();
  }

  private void validateFields(final List<SszField> fields) {
    for (int i = 0; i < fields.size(); i++) {
      final int fieldIndex = fields.get(i).getIndex();
      checkArgument(
          fieldIndex == i,
          "BeaconStateSchema fields must be ordered and contiguous.  Encountered unexpected index %s at fields element %s",
          fieldIndex,
          i);
    }

    final List<SszField> invariantFields = BeaconStateInvariants.getInvariantFields();
    checkArgument(
        fields.size() >= invariantFields.size(),
        "Must provide at least %s fields",
        invariantFields.size());
    for (SszField invariantField : invariantFields) {
      final int invariantIndex = invariantField.getIndex();
      final SszField actualField = fields.get(invariantIndex);
      checkArgument(
          actualField.equals(invariantField),
          "Expected invariant field '%s' at index %s, but got '%s'",
          invariantField.getName(),
          invariantIndex,
          actualField.getName());
    }
  }
}
