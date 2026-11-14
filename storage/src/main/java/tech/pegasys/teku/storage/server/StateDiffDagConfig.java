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

package tech.pegasys.teku.storage.server;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Configuration of state diff storage.
 *
 * @param enabled whether hot states are stored as state diff DAG nodes
 * @param retainedSlots on an archive database, the slots of finalized history kept in the DAG
 *     before the finalized checkpoint (the retention window); 0 keeps all of it. Applies whenever
 *     the database uses state diff storage, also when it was enabled by the database's marker
 *     rather than by this configuration
 */
public record StateDiffDagConfig(boolean enabled, long retainedSlots) {

  public StateDiffDagConfig(final boolean enabled, final long retainedSlots) {
    checkArgument(retainedSlots >= 0, "Retained slots must not be negative: %s", retainedSlots);
    this.enabled = enabled;
    this.retainedSlots = retainedSlots;
  }

  public static StateDiffDagConfig disabled() {
    return new StateDiffDagConfig(false, 0);
  }
}
