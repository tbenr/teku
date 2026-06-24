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

package tech.pegasys.teku.networking.eth2.gossip;

import java.util.OptionalInt;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.infrastructure.ssz.sos.SszLengthBounds;
import tech.pegasys.teku.networking.eth2.SszNetworkSchemaBounds;

/**
 * Resolves the network-facing {@link SszLengthBounds} for a schema carried by a gossip topic or RPC
 * protocol. Setup code passes one of these into codecs so decoding/decompression enforce the
 * resolved bounds rather than the raw schema bounds.
 */
public interface NetworkSszLengthBoundsProvider {

  SszLengthBounds getBounds(String networkSurface, SszSchema<?> schema);

  /** Provider that always uses the raw schema bounds (no type-specific network override). */
  static NetworkSszLengthBoundsProvider raw() {
    return (networkSurface, schema) ->
        SszNetworkSchemaBounds.resolve(networkSurface, schema, OptionalInt.empty());
  }
}
