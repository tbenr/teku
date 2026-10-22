/*
 * Copyright (c) 2026 Consensys Incorporated. All rights reserved.
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

package tech.pegasys.teku.infrastructure.ssz.tree;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.schema.SszPrimitiveSchemas;

class SszSuperNodeTest {

  private static final SszNodeTemplate UINT64_TEMPLATE =
      SszNodeTemplate.createFromType(SszPrimitiveSchemas.UINT64_SCHEMA);

  private final SszSuperNode superNode =
      new SszSuperNode(2, UINT64_TEMPLATE, Bytes.random(3 * Long.BYTES));
  private final SszSuperNode replacement =
      new SszSuperNode(2, UINT64_TEMPLATE, Bytes.random(4 * Long.BYTES));

  @Test
  void exposesDepth() {
    assertThat(superNode.getDepth()).isEqualTo(2);
  }

  @Test
  void updatedAtSelfReturnsNewNode() {
    assertThat(superNode.updated(GIndexUtil.SELF_G_INDEX, replacement)).isSameAs(replacement);
  }

  @Test
  void batchUpdateThroughParentReplacesWholeNode() {
    final TreeNode parent = BranchNode.create(superNode, LeafNode.EMPTY_LEAF);

    final TreeNode updated =
        parent.updated(
            new TreeUpdates(List.of(GIndexUtil.LEFT_CHILD_G_INDEX), List.of(replacement)));

    assertThat(updated.get(GIndexUtil.LEFT_CHILD_G_INDEX)).isSameAs(replacement);
    assertThat(updated.hashTreeRoot())
        .isEqualTo(BranchNode.create(replacement, LeafNode.EMPTY_LEAF).hashTreeRoot());
  }
}
