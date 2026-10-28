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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Loads the content of a supernode referenced by hash from a {@link TreeDiff}. The hash is the
 * supernode's hash tree root, and {@link TreeDiffApplier} trusts the loaded content to match it.
 */
@FunctionalInterface
public interface SupernodeSource {

  Optional<Bytes> load(Bytes32 hash);

  /**
   * Loads the supernodes {@code hashes}, by hash, omitting those the source lacks. This default
   * loads them one by one; a source over a store with batched point lookups reads them at once.
   */
  default Map<Bytes32, Bytes> loadAll(final Collection<Bytes32> hashes) {
    final Map<Bytes32, Bytes> loaded = new HashMap<>();
    for (final Bytes32 hash : hashes) {
      load(hash).ifPresent(ssz -> loaded.put(hash, ssz));
    }
    return loaded;
  }
}
