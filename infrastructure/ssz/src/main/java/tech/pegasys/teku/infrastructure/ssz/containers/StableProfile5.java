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
import tech.pegasys.teku.infrastructure.ssz.impl.AbstractSszImmutableStableContainer;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;

/** Autogenerated by tech.pegasys.teku.ssz.backing.ContainersGenerator */
public class StableProfile5<
        C extends StableProfile5<C, V0, V1, V2, V3, V4>,
        V0 extends SszData,
        V1 extends SszData,
        V2 extends SszData,
        V3 extends SszData,
        V4 extends SszData>
    extends AbstractSszImmutableStableContainer {

  protected StableProfile5(final StableProfileSchema5<C, V0, V1, V2, V3, V4> schema) {
    super(schema);
  }

  protected StableProfile5(
      final StableProfileSchema5<C, V0, V1, V2, V3, V4> schema, final TreeNode backingNode) {
    super(schema, backingNode);
  }

  protected StableProfile5(
      final StableProfileSchema5<C, V0, V1, V2, V3, V4> schema,
      final V0 arg0,
      final V1 arg1,
      final V2 arg2,
      final V3 arg3,
      final V4 arg4) {
    super(schema, arg0, arg1, arg2, arg3, arg4);
  }

  protected V0 getField0() {
    return getAny(0);
  }

  protected V1 getField1() {
    return getAny(1);
  }

  protected V2 getField2() {
    return getAny(2);
  }

  protected V3 getField3() {
    return getAny(3);
  }

  protected V4 getField4() {
    return getAny(4);
  }
}
