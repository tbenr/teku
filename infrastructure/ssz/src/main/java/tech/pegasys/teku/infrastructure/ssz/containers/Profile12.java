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

import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.impl.SszProfileImpl;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;

/** Autogenerated by tech.pegasys.teku.ssz.backing.ContainersGenerator */
public class Profile12<
        C extends Profile12<C, V0, V1, V2, V3, V4, V5, V6, V7, V8, V9, V10, V11>,
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
        V11 extends SszData>
    extends SszProfileImpl {
  final ProfileSchema12<C, V0, V1, V2, V3, V4, V5, V6, V7, V8, V9, V10, V11> schemaCache;

  protected Profile12(
      final ProfileSchema12<C, V0, V1, V2, V3, V4, V5, V6, V7, V8, V9, V10, V11> schema) {
    super(schema);
    this.schemaCache = schema;
  }

  protected Profile12(
      final ProfileSchema12<C, V0, V1, V2, V3, V4, V5, V6, V7, V8, V9, V10, V11> schema,
      final TreeNode backingNode) {
    super(schema, backingNode);
    this.schemaCache = schema;
  }

  protected Profile12(
      final ProfileSchema12<C, V0, V1, V2, V3, V4, V5, V6, V7, V8, V9, V10, V11> schema,
      final V0 arg0,
      final V1 arg1,
      final V2 arg2,
      final V3 arg3,
      final V4 arg4,
      final V5 arg5,
      final V6 arg6,
      final V7 arg7,
      final V8 arg8,
      final V9 arg9,
      final V10 arg10,
      final V11 arg11) {
    super(schema, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11);
    this.schemaCache = schema;
  }

  protected V0 getField0() {
    return getAny(schemaCache.mapToIndex(0));
  }

  protected V1 getField1() {
    return getAny(schemaCache.mapToIndex(1));
  }

  protected V2 getField2() {
    return getAny(schemaCache.mapToIndex(2));
  }

  protected V3 getField3() {
    return getAny(schemaCache.mapToIndex(3));
  }

  protected V4 getField4() {
    return getAny(schemaCache.mapToIndex(4));
  }

  protected V5 getField5() {
    return getAny(schemaCache.mapToIndex(5));
  }

  protected V6 getField6() {
    return getAny(schemaCache.mapToIndex(6));
  }

  protected V7 getField7() {
    return getAny(schemaCache.mapToIndex(7));
  }

  protected V8 getField8() {
    return getAny(schemaCache.mapToIndex(8));
  }

  protected V9 getField9() {
    return getAny(schemaCache.mapToIndex(9));
  }

  protected V10 getField10() {
    return getAny(schemaCache.mapToIndex(10));
  }

  protected V11 getField11() {
    return getAny(schemaCache.mapToIndex(11));
  }
}
