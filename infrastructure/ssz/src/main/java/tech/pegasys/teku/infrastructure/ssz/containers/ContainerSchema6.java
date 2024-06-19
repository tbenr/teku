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

import java.util.List;
import java.util.function.BiFunction;
import tech.pegasys.teku.infrastructure.ssz.SszContainer;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.impl.AbstractSszContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;

/** Autogenerated by tech.pegasys.teku.ssz.backing.ContainersGenerator */
public abstract class ContainerSchema6<
        C extends SszContainer, V0 extends SszData, V1 extends SszData, V2 extends SszData, V3 extends SszData, V4 extends SszData, V5 extends SszData>
    extends AbstractSszContainerSchema<C> {

  public static <
          C extends SszContainer, V0 extends SszData, V1 extends SszData, V2 extends SszData, V3 extends SszData, V4 extends SszData, V5 extends SszData>
      ContainerSchema6<C, V0, V1, V2, V3, V4, V5>
          create(
              final SszSchema<V0> fieldSchema0, final SszSchema<V1> fieldSchema1, final SszSchema<V2> fieldSchema2, final SszSchema<V3> fieldSchema3, final SszSchema<V4> fieldSchema4, final SszSchema<V5> fieldSchema5,
              final BiFunction<
                      ContainerSchema6<
                          C, V0, V1, V2, V3, V4, V5>,
                      TreeNode,
                      C>
                  instanceCtor) {
    return new ContainerSchema6<>(
        fieldSchema0, fieldSchema1, fieldSchema2, fieldSchema3, fieldSchema4, fieldSchema5) {
      @Override
      public C createFromBackingNode(final TreeNode node) {
        return instanceCtor.apply(this, node);
      }
    };
  }

  protected ContainerSchema6(
      final SszSchema<V0> fieldSchema0, final SszSchema<V1> fieldSchema1, final SszSchema<V2> fieldSchema2, final SszSchema<V3> fieldSchema3, final SszSchema<V4> fieldSchema4, final SszSchema<V5> fieldSchema5) {

    super(List.of(fieldSchema0, fieldSchema1, fieldSchema2, fieldSchema3, fieldSchema4, fieldSchema5));
  }

  protected ContainerSchema6(
      final String containerName,
      final NamedSchema<V0> fieldNamedSchema0, final NamedSchema<V1> fieldNamedSchema1, final NamedSchema<V2> fieldNamedSchema2, final NamedSchema<V3> fieldNamedSchema3, final NamedSchema<V4> fieldNamedSchema4, final NamedSchema<V5> fieldNamedSchema5) {

    super(containerName, List.of(fieldNamedSchema0, fieldNamedSchema1, fieldNamedSchema2, fieldNamedSchema3, fieldNamedSchema4, fieldNamedSchema5));
  }

    @SuppressWarnings("unchecked")
  public SszSchema<V0> getFieldSchema0() {
    return (SszSchema<V0>) getChildSchema(0);
  }


  @SuppressWarnings("unchecked")
  public SszSchema<V1> getFieldSchema1() {
    return (SszSchema<V1>) getChildSchema(1);
  }


  @SuppressWarnings("unchecked")
  public SszSchema<V2> getFieldSchema2() {
    return (SszSchema<V2>) getChildSchema(2);
  }


  @SuppressWarnings("unchecked")
  public SszSchema<V3> getFieldSchema3() {
    return (SszSchema<V3>) getChildSchema(3);
  }


  @SuppressWarnings("unchecked")
  public SszSchema<V4> getFieldSchema4() {
    return (SszSchema<V4>) getChildSchema(4);
  }


  @SuppressWarnings("unchecked")
  public SszSchema<V5> getFieldSchema5() {
    return (SszSchema<V5>) getChildSchema(5);
  }

}
