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

import java.util.Collection;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Loads the content of the supernodes referenced by hash from a {@link TreeDiff}. The hash is the
 * supernode's hash tree root, and {@link TreeDiffApplier} trusts the loaded content to match it.
 */
@FunctionalInterface
public interface SupernodeSource {

  /**
   * Loads the supernodes {@code hashes}, by hash, omitting those the source lacks. A source over a
   * store with batched point lookups reads them at once.
   */
  Map<Bytes32, Bytes> loadAll(Collection<Bytes32> hashes);
}
