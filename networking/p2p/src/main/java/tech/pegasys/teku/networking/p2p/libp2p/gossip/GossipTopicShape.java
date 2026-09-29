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

package tech.pegasys.teku.networking.p2p.libp2p.gossip;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reduces a gossip topic to a bounded label value.
 *
 * <p>A full topic is {@code /eth2/<forkDigest>/<name>/<encoding>}, and {@code <name>} carries a
 * subnet index for the subnet topics. Used directly as a metric label that is unbounded in
 * practice: the fork digest rotates at every fork and the subnet index multiplies every series by
 * the subnet count. This keeps only the topic shape - the name with its trailing subnet index
 * removed - so the label is drawn from the fixed set of topic kinds the spec defines.
 *
 * <p>Anything that does not parse collapses to {@value #OTHER} rather than minting a new series.
 */
public class GossipTopicShape {

  public static final String OTHER = "other";

  private static final Pattern TOPIC = Pattern.compile("^/eth2/[0-9a-fA-F]+/([^/]+)/[^/]+$");
  private static final Pattern TRAILING_INDEX = Pattern.compile("_\\d+$");

  private GossipTopicShape() {}

  public static String of(final String topic) {
    if (topic == null) {
      return OTHER;
    }
    final Matcher matcher = TOPIC.matcher(topic);
    if (!matcher.matches()) {
      return OTHER;
    }
    return TRAILING_INDEX.matcher(matcher.group(1)).replaceFirst("");
  }
}
