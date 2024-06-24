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
public abstract class ProfileSchema13<
        C extends SszProfile,
        V0 extends SszData,
        V1 extends SszData,
        V2 extends SszData,
        V3 extends SszData,
        V4 extends SszData,
        V5 extends SszData,
        V6 extends SszData,
        V7 extends SszData,
        V8 extends SszData,
        V9 extends SszData,
        V10 extends SszData,
        V11 extends SszData,
        V12 extends SszData>
    extends AbstractSszProfileSchema<C> {

  public static <
          C extends SszProfile,
          V0 extends SszData,
          V1 extends SszData,
          V2 extends SszData,
          V3 extends SszData,
          V4 extends SszData,
          V5 extends SszData,
          V6 extends SszData,
          V7 extends SszData,
          V8 extends SszData,
          V9 extends SszData,
          V10 extends SszData,
          V11 extends SszData,
          V12 extends SszData>
      ProfileSchema13<C, V0, V1, V2, V3, V4, V5, V6, V7, V8, V9, V10, V11, V12> create(
          final SszStableContainerSchema<? extends SszStableContainer> stableContainerSchema,
          final Set<Integer> activeFieldIndices,
          final BiFunction<
                  ProfileSchema13<C, V0, V1, V2, V3, V4, V5, V6, V7, V8, V9, V10, V11, V12>,
                  TreeNode,
                  C>
              instanceCtor) {
    return new ProfileSchema13<>("", stableContainerSchema, activeFieldIndices) {
      @Override
      public C createFromBackingNode(final TreeNode node) {
        return instanceCtor.apply(this, node);
      }
    };
  }

  protected ProfileSchema13(
      final String containerName,
      final SszSchema<V0> fieldSchema0,
      final SszSchema<V1> fieldSchema1,
      final SszSchema<V2> fieldSchema2,
      final SszSchema<V3> fieldSchema3,
      final SszSchema<V4> fieldSchema4,
      final SszSchema<V5> fieldSchema5,
      final SszSchema<V6> fieldSchema6,
      final SszSchema<V7> fieldSchema7,
      final SszSchema<V8> fieldSchema8,
      final SszSchema<V9> fieldSchema9,
      final SszSchema<V10> fieldSchema10,
      final SszSchema<V11> fieldSchema11,
      final SszSchema<V12> fieldSchema12,
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
                fieldSchema6,
                fieldSchema7,
                fieldSchema8,
                fieldSchema9,
                fieldSchema10,
                fieldSchema11,
                fieldSchema12)),
        IntStream.range(0, 13).boxed().collect(Collectors.toUnmodifiableSet()));
  }

  protected ProfileSchema13(
      final String containerName,
      final NamedSchema<V0> fieldNamedSchema0,
      final NamedSchema<V1> fieldNamedSchema1,
      final NamedSchema<V2> fieldNamedSchema2,
      final NamedSchema<V3> fieldNamedSchema3,
      final NamedSchema<V4> fieldNamedSchema4,
      final NamedSchema<V5> fieldNamedSchema5,
      final NamedSchema<V6> fieldNamedSchema6,
      final NamedSchema<V7> fieldNamedSchema7,
      final NamedSchema<V8> fieldNamedSchema8,
      final NamedSchema<V9> fieldNamedSchema9,
      final NamedSchema<V10> fieldNamedSchema10,
      final NamedSchema<V11> fieldNamedSchema11,
      final NamedSchema<V12> fieldNamedSchema12,
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
                    fieldNamedSchema6,
                    fieldNamedSchema7,
                    fieldNamedSchema8,
                    fieldNamedSchema9,
                    fieldNamedSchema10,
                    fieldNamedSchema11,
                    fieldNamedSchema12))),
        IntStream.range(0, 13).boxed().collect(Collectors.toUnmodifiableSet()));
  }

  protected ProfileSchema13(
      final String containerName,
      final SszStableContainerSchema<? extends SszStableContainer> stableContainerSchema,
      final Set<Integer> activeFieldIndices) {

    super(containerName, stableContainerSchema, activeFieldIndices);

    assert activeFieldIndices.size() == 13;
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

  @SuppressWarnings("unchecked")
  public SszSchema<V7> getFieldSchema7() {
    return (SszSchema<V7>) getNthActiveFieldSchema(7);
  }

  @SuppressWarnings("unchecked")
  public SszSchema<V8> getFieldSchema8() {
    return (SszSchema<V8>) getNthActiveFieldSchema(8);
  }

  @SuppressWarnings("unchecked")
  public SszSchema<V9> getFieldSchema9() {
    return (SszSchema<V9>) getNthActiveFieldSchema(9);
  }

  @SuppressWarnings("unchecked")
  public SszSchema<V10> getFieldSchema10() {
    return (SszSchema<V10>) getNthActiveFieldSchema(10);
  }

  @SuppressWarnings("unchecked")
  public SszSchema<V11> getFieldSchema11() {
    return (SszSchema<V11>) getNthActiveFieldSchema(11);
  }

  @SuppressWarnings("unchecked")
  public SszSchema<V12> getFieldSchema12() {
    return (SszSchema<V12>) getNthActiveFieldSchema(12);
  }
}
