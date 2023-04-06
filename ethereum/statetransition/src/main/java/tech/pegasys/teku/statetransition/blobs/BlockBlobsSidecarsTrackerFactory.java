/*
 * Copyright ConsenSys Software Inc., 2023
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

package tech.pegasys.teku.statetransition.blobs;

import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.execution.versions.deneb.BlobSidecar;

public class BlockBlobsSidecarsTrackerFactory {

  private final Spec spec;
  private final AsyncRunner asyncRunner;
  private final BlockBlobsSidecarsTrackerTimingStrategy blockBlobsSidecarsTrackerTimingStrategy;

  public BlockBlobsSidecarsTrackerFactory(
      final Spec spec,
      final AsyncRunner asyncRunner,
      final BlockBlobsSidecarsTrackerTimingStrategy blockBlobsSidecarsTrackerTimingStrategy) {
    this.spec = spec;
    this.asyncRunner = asyncRunner;
    this.blockBlobsSidecarsTrackerTimingStrategy = blockBlobsSidecarsTrackerTimingStrategy;
  }

  public BlockBlobSidecarsTracker createFrom(final BlobSidecar blobSidecar) {
    return BlockBlobSidecarsTracker.createFrom(
        blobSidecar, spec, asyncRunner, blockBlobsSidecarsTrackerTimingStrategy);
  }

  public BlockBlobSidecarsTracker createFrom(final SignedBeaconBlock block) {
    return BlockBlobSidecarsTracker.createFrom(
        block, spec, asyncRunner, blockBlobsSidecarsTrackerTimingStrategy);
  }
}
