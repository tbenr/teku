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

package tech.pegasys.teku.networking.eth2;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.OptionalInt;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.infrastructure.ssz.sos.SszLengthBounds;

/**
 * Resolves the {@link SszLengthBounds} that network code should enforce for a given SSZ schema.
 *
 * <p>SSZ schemas report raw structural bounds via {@link SszSchema#getSszLengthBounds()};
 * progressive (EIP-7916) schemas are raw-unbounded. A network surface (gossip topic, RPC protocol)
 * may supply a type-specific finite max-size override, but only for schemas whose raw bounds are
 * unbounded. For raw-finite schemas the raw bounds always stand, and an attempt to override them is
 * a setup error.
 *
 * <p>When a network-exposed schema is raw-unbounded and no override is supplied, this logs a
 * warning at setup time rather than failing, so not-yet-final or internal paths can transition
 * gradually.
 */
public final class SszNetworkSchemaBounds {

  private static final Logger LOG = LogManager.getLogger();

  private SszNetworkSchemaBounds() {}

  public static SszLengthBounds resolve(
      final String networkSurface, final SszSchema<?> schema, final OptionalInt networkMaxBytes) {
    final SszLengthBounds rawBounds = schema.getSszLengthBounds();
    if (networkMaxBytes.isEmpty()) {
      if (rawBounds.isUnbounded()) {
        LOG.warn(
            "SSZ schema {} used by {} has raw-unbounded getSszLengthBounds() and no network "
                + "max-size override",
            schema.getName().orElse(schema.toString()),
            networkSurface);
      }
      return rawBounds;
    }

    checkArgument(
        rawBounds.isUnbounded(),
        "Network SSZ max-size override for %s is only allowed for unbounded raw SSZ schemas",
        networkSurface);

    checkArgument(
        networkMaxBytes.getAsInt() >= rawBounds.getMinBytes(),
        "Network SSZ max-size override for %s is below the raw minimum SSZ size",
        networkSurface);
    return SszLengthBounds.ofBytes(rawBounds.getMinBytes(), networkMaxBytes.getAsInt());
  }
}
