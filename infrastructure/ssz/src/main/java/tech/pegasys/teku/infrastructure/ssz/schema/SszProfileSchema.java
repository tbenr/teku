/*
 * Copyright Consensys Software Inc., 2024
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

package tech.pegasys.teku.infrastructure.ssz.schema;

import java.util.List;
import java.util.Optional;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.SszProfile;
import tech.pegasys.teku.infrastructure.ssz.SszStableContainer;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitvector;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;

public interface SszProfileSchema<C extends SszProfile> extends SszStableContainerSchema<C> {
  @Override
  default TreeNode createTreeFromOptionalFieldValues(
      final List<Optional<? extends SszData>> fieldValues) {
    throw new UnsupportedOperationException();
  }

  SszStableContainerSchema<? extends SszStableContainer> getStableContainerSchema();

  boolean isActiveField(int index);

  int getActiveFieldCount();

  SszBitvector getActiveFields();

  default List<? extends SszSchema<?>> getActiveChildrenSchemas() {
    return getActiveFields().streamAllSetBits().mapToObj(this::getChildSchema).toList();
  }

  /**
   * This method resolves the index of the nth active field.
   *
   * @param nthActiveField Nth active field
   * @return index
   */
  int getNthActiveFieldIndex(int nthActiveField);

  default SszSchema<?> getNthActiveFieldSchema(final int nthActiveField) {
    return getChildSchema(getNthActiveFieldIndex(nthActiveField));
  }

  @Override
  default Optional<SszStableContainerSchema<?>> toStableContainerSchema() {
    return Optional.of(getStableContainerSchema());
  }

  @Override
  @SuppressWarnings("unchecked")
  default Optional<SszProfileSchema<?>> toProfileSchema() {
    return Optional.of(this);
  }
}