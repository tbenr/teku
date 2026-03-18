/*
 * Copyright Consensys Software Inc., 2026
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

package tech.pegasys.teku.storage.protoarray;

import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * Compares FULL vs EMPTY children of the same PENDING parent node for fork-choice head selection.
 *
 * <p>Implements the comparison aspect of spec function get_payload_status_tiebreaker, used as the
 * third sort key in get_head.
 *
 * <p>Spec: get_head — max(children, key=lambda child: (get_weight(...), child.root,
 * get_payload_status_tiebreaker(...)))
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#modified-get_head
 */
interface PayloadStatusTiebreaker {
  /**
   * Compare FULL vs EMPTY children of the same PENDING parent. Returns &gt; 0 if child should win,
   * &lt; 0 if bestChild should win, 0 if tied.
   */
  int compare(ProtoNode child, ProtoNode bestChild, ProtoNode parent, ProtoArray protoArray);

  /**
   * Returns the effective weight for a node, implementing the spec's get_weight zero-weight rule.
   *
   * <p>Spec: get_weight returns 0 for non-PENDING nodes at current_slot - 1 (EMPTY/FULL at the slot
   * immediately before the current slot).
   */
  UInt64 effectiveWeight(ProtoNode node);
}
