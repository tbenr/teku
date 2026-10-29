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

package tech.pegasys.teku.infrastructure.statediff;

import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.infrastructure.ssz.tree.FrozenSuperNode;
import tech.pegasys.teku.infrastructure.ssz.tree.SszNodeTemplate;
import tech.pegasys.teku.infrastructure.ssz.tree.SszSuperNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.SupernodeEntry;

/**
 * Chooses the node {@link TreeDiffApplier} places for a supernode a diff references whole: a plain
 * {@link SszSuperNode} holding the loaded content, or a {@link FrozenSuperNode} that keeps only its
 * hash and reloads the content from the store when read. The applier stays ignorant of what the
 * elements are; the freezer decides from the field index and the content.
 *
 * <p>Exploration only (state diff tier 3 attempt, 2026-10-29).
 */
@FunctionalInterface
public interface SupernodeFreezer {

  /** Never freezes: the content is kept in a supernode with the referenced root. */
  SupernodeFreezer NONE =
      (entry, template, ssz) ->
          SszSuperNode.createWithKnownRoot(entry.depth(), template, ssz, entry.hash());

  /**
   * The node to place for {@code entry}, whose content {@code ssz} was loaded from the store under
   * {@code entry.hash()}; {@code template} is the element template of its field.
   */
  TreeNode supernode(SupernodeEntry entry, SszNodeTemplate template, Bytes ssz);
}
