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

import static tech.pegasys.teku.infrastructure.ssz.schema.impl.AbstractSszStableContainerSchema.continuousActiveNamedSchemas;
import static tech.pegasys.teku.infrastructure.ssz.schema.impl.AbstractSszStableContainerSchema.continuousActiveSchemas;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.SszProfile;
import tech.pegasys.teku.infrastructure.ssz.SszStableContainer;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszStableContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.impl.AbstractSszProfileSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;

/** Autogenerated by tech.pegasys.teku.ssz.backing.ContainersGenerator */
public abstract class ProfileSchema5<
        C extends SszProfile, V0 extends SszData, V1 extends SszData, V2 extends SszData, V3 extends SszData, V4 extends SszData>
    extends AbstractSszProfileSchema<C> {

  public static <
          C extends SszProfile, V0 extends SszData, V1 extends SszData, V2 extends SszData, V3 extends SszData, V4 extends SszData>
      ProfileSchema5<C, V0, V1, V2, V3, V4> create(
          final SszStableContainerSchema<? extends SszStableContainer> stableContainerSchema,
          final Set<Integer> activeFieldIndices,
          final BiFunction<
                  ProfileSchema5<
                      C, V0, V1, V2, V3, V4>,
                  TreeNode,
                  C>
              instanceCtor) {
    return new ProfileSchema5<>(
        "", stableContainerSchema, activeFieldIndices) {
      @Override
      public C createFromBackingNode(final TreeNode node) {
        return instanceCtor.apply(this, node);
      }
    };
  }

  protected ProfileSchema5(
      final String containerName,
      final SszSchema<V0> fieldSchema0, final SszSchema<V1> fieldSchema1, final SszSchema<V2> fieldSchema2, final SszSchema<V3> fieldSchema3, final SszSchema<V4> fieldSchema4,
      final int maxFieldCount) {
    this(
        containerName,
        SszStableContainerSchema.createForProfileOnly(
            maxFieldCount, continuousActiveSchemas(fieldSchema0, fieldSchema1, fieldSchema2, fieldSchema3, fieldSchema4)),
        IntStream.range(0, 5)
            .boxed()
            .collect(Collectors.toUnmodifiableSet()));
  }

  protected ProfileSchema5(
      final String containerName,
      final NamedSchema<V0> fieldNamedSchema0, final NamedSchema<V1> fieldNamedSchema1, final NamedSchema<V2> fieldNamedSchema2, final NamedSchema<V3> fieldNamedSchema3, final NamedSchema<V4> fieldNamedSchema4,
      final int maxFieldCount) {
    this(
        containerName,
        SszStableContainerSchema.createForProfileOnly(
            maxFieldCount,
            continuousActiveNamedSchemas(
                List.of(fieldNamedSchema0, fieldNamedSchema1, fieldNamedSchema2, fieldNamedSchema3, fieldNamedSchema4))),
        IntStream.range(0, 5)
            .boxed()
            .collect(Collectors.toUnmodifiableSet()));
  }

  protected ProfileSchema5(
      final String containerName,
      final SszStableContainerSchema<? extends SszStableContainer> stableContainerSchema,
      final Set<Integer> activeFieldIndices) {

    super(containerName, stableContainerSchema, activeFieldIndices);

    assert activeFieldIndices.size() == 5;
  }

    @SuppressWarnings("unchecked")
  public SszSchema<V0> getFieldSchema0() {
    return (SszSchema<V0>) getNthActiveFieldSchema(0);
  }


  @SuppressWarnings("unchecked")
  public SszSchema<V1> getFieldSchema1() {
    return (SszSchema<V1>) getNthActiveFieldSchema(1);
  }


  @SuppressWarnings("unchecked")
  public SszSchema<V2> getFieldSchema2() {
    return (SszSchema<V2>) getNthActiveFieldSchema(2);
  }


  @SuppressWarnings("unchecked")
  public SszSchema<V3> getFieldSchema3() {
    return (SszSchema<V3>) getNthActiveFieldSchema(3);
  }


  @SuppressWarnings("unchecked")
  public SszSchema<V4> getFieldSchema4() {
    return (SszSchema<V4>) getNthActiveFieldSchema(4);
  }

}
