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

package tech.pegasys.teku.validator.api;

import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;

/**
 * @param rejectionReason why the envelope was not published or not imported, empty when it was
 *     published and imported
 */
public record PublishSignedExecutionPayloadResult(
    Bytes32 beaconBlockRoot, Outcome outcome, Optional<String> rejectionReason) {

  public enum Outcome {
    PUBLISHED,
    /** Broadcast, but the envelope failed to be imported */
    PUBLISHED_NOT_IMPORTED,
    REJECTED,
    /** The beacon block of the envelope has not been seen, so nothing was published */
    BLOCK_NOT_FOUND
  }

  public static PublishSignedExecutionPayloadResult success(final Bytes32 beaconBlockRoot) {
    return new PublishSignedExecutionPayloadResult(
        beaconBlockRoot, Outcome.PUBLISHED, Optional.empty());
  }

  public static PublishSignedExecutionPayloadResult notImported(
      final Bytes32 beaconBlockRoot, final String reason) {
    return new PublishSignedExecutionPayloadResult(
        beaconBlockRoot, Outcome.PUBLISHED_NOT_IMPORTED, Optional.of(reason));
  }

  public static PublishSignedExecutionPayloadResult rejected(
      final Bytes32 beaconBlockRoot, final String reason) {
    return new PublishSignedExecutionPayloadResult(
        beaconBlockRoot, Outcome.REJECTED, Optional.of(reason));
  }

  public static PublishSignedExecutionPayloadResult blockNotFound(final Bytes32 beaconBlockRoot) {
    return new PublishSignedExecutionPayloadResult(
        beaconBlockRoot, Outcome.BLOCK_NOT_FOUND, Optional.of("Beacon block not found"));
  }

  public boolean isPublished() {
    return outcome == Outcome.PUBLISHED || outcome == Outcome.PUBLISHED_NOT_IMPORTED;
  }
}
