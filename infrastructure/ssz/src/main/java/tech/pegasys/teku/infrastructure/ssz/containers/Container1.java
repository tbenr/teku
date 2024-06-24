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
import tech.pegasys.teku.infrastructure.ssz.impl.AbstractSszImmutableContainer;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;

/** Autogenerated by tech.pegasys.teku.ssz.backing.ContainersGenerator */
public class Container1<
        C extends
            Container1<C, V0>,
        V0 extends SszData>
    extends AbstractSszImmutableContainer {

  protected Container1(
      final ContainerSchema1<C, V0>
          schema) {
    super(schema);
  }

  protected Container1(
      final ContainerSchema1<C, V0>
          schema,
      final TreeNode backingNode) {
    super(schema, backingNode);
  }

  protected Container1(
      final ContainerSchema1<C, V0>
          schema, final V0 arg0) {
    super(schema, arg0);
  }

  protected V0 getField0() {
    return getAny(0);
  }
}
