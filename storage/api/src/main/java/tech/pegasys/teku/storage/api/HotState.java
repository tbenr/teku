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

package tech.pegasys.teku.storage.api;

import java.util.Optional;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;

/**
 * A hot state to persist and, when the store persists checkpoint block states as state diff DAG
 * nodes, where it sits in the DAG. A store persisting every n-th epoch boundary state gives no
 * placement; a store persisting checkpoint block states always does.
 */
public record HotState(BeaconState state, Optional<HotStateNodeInfo> nodeInfo) {

  /** A state persisted whole, without a place in the state diff DAG. */
  public static HotState withoutNodeInfo(final BeaconState state) {
    return new HotState(state, Optional.empty());
  }

  /** A checkpoint block state persisted as a state diff DAG node placed by {@code nodeInfo}. */
  public static HotState withNodeInfo(final BeaconState state, final HotStateNodeInfo nodeInfo) {
    return new HotState(state, Optional.of(nodeInfo));
  }
}
