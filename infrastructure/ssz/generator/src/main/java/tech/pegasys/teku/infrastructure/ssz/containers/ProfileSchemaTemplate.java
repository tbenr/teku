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

package tech.pegasys.teku.infrastructure.ssz.containers;

import java.util.Set;
import java.util.function.BiFunction;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.SszProfile;
import tech.pegasys.teku.infrastructure.ssz.SszStableContainer;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszStableContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.impl.AbstractSszProfileSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;

/** Autogenerated by tech.pegasys.teku.ssz.backing.ContainersGenerator */
public abstract class /*$$TypeClassName*/ ProfileSchemaTemplate /*$$*/<
        C extends SszProfile, /*$$ViewTypes*/ V0 extends SszData, V1 extends SszData /*$$*/>
    extends AbstractSszProfileSchema<C> {

  public static <
          C extends SszProfile, /*$$ViewTypes*/ V0 extends SszData, V1 extends SszData /*$$*/>
      /*$$TypeClassName*/ ProfileSchemaTemplate /*$$*/<C, /*$$ViewTypeNames*/ V0, V1 /*$$*/>
          create(
          final SszStableContainerSchema<? extends SszStableContainer> stableContainerSchema,
          final Set<Integer> activeFieldIndices,
              final BiFunction<
                      /*$$TypeClassName*/ ProfileSchemaTemplate /*$$*/<
                                                C, /*$$ViewTypeNames*/ V0, V1 /*$$*/>,
                      TreeNode,
                      C>
                  instanceCtor) {
    return new /*$$TypeClassName*/ ProfileSchemaTemplate /*$$*/<>(
            "",
            stableContainerSchema, activeFieldIndices) {
      @Override
      public C createFromBackingNode(final TreeNode node) {
        return instanceCtor.apply(this, node);
      }
    };
  }


  protected /*$$TypeClassName*/ ProfileSchemaTemplate /*$$*/(
      final String containerName,
      final SszStableContainerSchema<? extends SszStableContainer> stableContainerSchema,
      final Set<Integer> activeFieldIndices) {

    super(
        containerName,
            stableContainerSchema,
            activeFieldIndices);

    assert activeFieldIndices.size() == /*$$NumberOfFields*/ 2 /*$$*/;
  }

  /*$$TypeGetters*/
  @SuppressWarnings("unchecked")
  public SszSchema<V0> getFieldSchema0() {
    return (SszSchema<V0>) getChildSchema(getNthActiveFieldIndex(0));
  }

  @SuppressWarnings("unchecked")
  public SszSchema<V1> getFieldSchema1() {
    return (SszSchema<V1>) getChildSchema(getNthActiveFieldIndex(1));
  }
  /*$$*/
}
