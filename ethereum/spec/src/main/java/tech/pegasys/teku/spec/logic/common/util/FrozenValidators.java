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

package tech.pegasys.teku.spec.logic.common.util;

import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.tree.FrozenRanges;
import tech.pegasys.teku.infrastructure.ssz.tree.FrozenSuperNode;
import tech.pegasys.teku.spec.datastructures.state.Validator;

/**
 * The validator index ranges held by frozen supernodes (state diff tier 3 attempt). Only the state
 * diff storage freezes supernodes, and only those whose validators all have a zero effective
 * balance, an activation epoch set, an exit epoch before the epoch of the node frozen and a
 * withdrawable epoch at or before it; that epoch is at or before the current epoch of every state
 * holding the supernode, since states only derive forward. The consensus fast paths rely on those
 * facts to skip frozen validators without reading them. Without any frozen supernode in the process
 * the ranges are empty at no cost, so every fast path is then a no-op.
 */
public final class FrozenValidators {

  private FrozenValidators() {}

  public static FrozenRanges ranges(final SszList<Validator> validators) {
    if (!FrozenSuperNode.anyCreated()) {
      return FrozenRanges.NONE;
    }
    return FrozenRanges.collectCached(
        validators.getSchema(), validators.getBackingNode(), validators.size());
  }
}
