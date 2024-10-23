/*
 * Copyright Consensys Software Inc., 2022
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

import com.google.common.base.Throwables;
import io.libp2p.core.SemiDuplexNoOutboundStreamException;
import io.libp2p.pubsub.MessageAlreadySeenException;
import io.libp2p.pubsub.NoPeersForOutboundMessageException;
import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

public class GossipFailureLogger {
  private static final Logger LOG = LogManager.getLogger();

  private final boolean shouldSuppress;
  private final String messageType;
  private Optional<UInt64> lastErroredSlot;
  private Throwable lastRootCause;

  public GossipFailureLogger(final String messageType, final boolean shouldSuppress) {
    this.messageType = messageType;
    this.shouldSuppress = shouldSuppress;
  }

  public synchronized void logWithSuppression(
      final Throwable error, final Optional<UInt64> maybeSlot) {
    final Throwable rootCause = Throwables.getRootCause(error);

    final boolean suppress;

    // can only try to suppress if we have a slot to compare
    if (shouldSuppress && maybeSlot.isPresent()) {
      suppress =
          maybeSlot.equals(lastErroredSlot)
              && rootCause.getClass().equals(lastRootCause.getClass());

    } else {
      suppress = false;
    }

    lastErroredSlot = maybeSlot;
    lastRootCause = rootCause;

    final String slotLog = maybeSlot.map(slot -> " for slot " + slot).orElse("");

    if (lastRootCause instanceof MessageAlreadySeenException) {
      LOG.debug(
          "Failed to publish {}(s){} because the message has already been seen",
          messageType,
          slotLog);
    } else if (lastRootCause instanceof NoPeersForOutboundMessageException) {
      LOG.log(
          suppress ? Level.DEBUG : Level.WARN,
          "Failed to publish {}(s){}; {}",
          messageType,
          slotLog,
          lastRootCause.getMessage());
    } else if (lastRootCause instanceof SemiDuplexNoOutboundStreamException) {
      LOG.log(
          suppress ? Level.DEBUG : Level.WARN,
          "Failed to publish {}(s){} because no active outbound stream for the required gossip topic",
          messageType,
          slotLog);
    } else {
      LOG.log(
          suppress ? Level.DEBUG : Level.ERROR,
          "Failed to publish {}(s){}",
          messageType,
          slotLog,
          error);
    }
  }
}
