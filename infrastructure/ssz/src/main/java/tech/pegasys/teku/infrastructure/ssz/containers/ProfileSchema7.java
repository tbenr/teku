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
public abstract class ProfileSchema7<
        C extends SszProfile,
        V0 extends SszData,
        V1 extends SszData,
        V2 extends SszData,
        V3 extends SszData,
        V4 extends SszData,
        V5 extends SszData,
        V6 extends SszData>
    extends AbstractSszProfileSchema<C> {

  public static <
          C extends SszProfile,
          V0 extends SszData,
          V1 extends SszData,
          V2 extends SszData,
          V3 extends SszData,
          V4 extends SszData,
          V5 extends SszData,
          V6 extends SszData>
      ProfileSchema7<C, V0, V1, V2, V3, V4, V5, V6> create(
          final SszStableContainerSchema<? extends SszStableContainer> stableContainerSchema,
          final Set<Integer> activeFieldIndices,
          final BiFunction<ProfileSchema7<C, V0, V1, V2, V3, V4, V5, V6>, TreeNode, C>
              instanceCtor) {
    return new ProfileSchema7<>("", stableContainerSchema, activeFieldIndices) {
      @Override
      public C createFromBackingNode(final TreeNode node) {
        return instanceCtor.apply(this, node);
      }
    };
  }

  protected ProfileSchema7(
      final String containerName,
      final SszSchema<V0> fieldSchema0,
      final SszSchema<V1> fieldSchema1,
      final SszSchema<V2> fieldSchema2,
      final SszSchema<V3> fieldSchema3,
      final SszSchema<V4> fieldSchema4,
      final SszSchema<V5> fieldSchema5,
      final SszSchema<V6> fieldSchema6,
      final int maxFieldCount) {
    this(
        containerName,
        SszStableContainerSchema.createForProfileOnly(
            maxFieldCount,
            continuousActiveSchemas(
                fieldSchema0,
                fieldSchema1,
                fieldSchema2,
                fieldSchema3,
                fieldSchema4,
                fieldSchema5,
                fieldSchema6)),
        IntStream.range(0, 7).boxed().collect(Collectors.toUnmodifiableSet()));
  }

  protected ProfileSchema7(
      final String containerName,
      final NamedSchema<V0> fieldNamedSchema0,
      final NamedSchema<V1> fieldNamedSchema1,
      final NamedSchema<V2> fieldNamedSchema2,
      final NamedSchema<V3> fieldNamedSchema3,
      final NamedSchema<V4> fieldNamedSchema4,
      final NamedSchema<V5> fieldNamedSchema5,
      final NamedSchema<V6> fieldNamedSchema6,
      final int maxFieldCount) {
    this(
        containerName,
        SszStableContainerSchema.createForProfileOnly(
            maxFieldCount,
            continuousActiveNamedSchemas(
                List.of(
                    fieldNamedSchema0,
                    fieldNamedSchema1,
                    fieldNamedSchema2,
                    fieldNamedSchema3,
                    fieldNamedSchema4,
                    fieldNamedSchema5,
                    fieldNamedSchema6))),
        IntStream.range(0, 7).boxed().collect(Collectors.toUnmodifiableSet()));
  }

  protected ProfileSchema7(
      final String containerName,
      final SszStableContainerSchema<? extends SszStableContainer> stableContainerSchema,
      final Set<Integer> activeFieldIndices) {

    super(containerName, stableContainerSchema, activeFieldIndices);

    assert activeFieldIndices.size() == 7;
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

  @SuppressWarnings("unchecked")
  public SszSchema<V5> getFieldSchema5() {
    return (SszSchema<V5>) getNthActiveFieldSchema(5);
  }

  @SuppressWarnings("unchecked")
  public SszSchema<V6> getFieldSchema6() {
    return (SszSchema<V6>) getNthActiveFieldSchema(6);
  }
}