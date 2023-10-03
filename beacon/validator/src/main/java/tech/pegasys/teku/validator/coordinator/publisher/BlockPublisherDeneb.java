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

package tech.pegasys.teku.validator.coordinator.publisher;

import java.util.List;
import java.util.concurrent.TimeUnit;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.networking.eth2.gossip.BlobSidecarGossipChannel;
import tech.pegasys.teku.networking.eth2.gossip.BlockGossipChannel;
import tech.pegasys.teku.spec.datastructures.blobs.versions.deneb.SignedBlobSidecar;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockContainer;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.BeaconBlockBody;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.versions.deneb.BeaconBlockBodyDeneb;
import tech.pegasys.teku.spec.logic.common.statetransition.results.BlockImportResult;
import tech.pegasys.teku.statetransition.blobs.BlobSidecarPool;
import tech.pegasys.teku.statetransition.block.BlockImportChannel;
import tech.pegasys.teku.validator.coordinator.BlockFactory;
import tech.pegasys.teku.validator.coordinator.DutyMetrics;
import tech.pegasys.teku.validator.coordinator.performance.PerformanceTracker;

public class BlockPublisherDeneb extends AbstractBlockPublisher {

  private final BlobSidecarPool blobSidecarPool;
  private final BlockGossipChannel blockGossipChannel;
  private final BlobSidecarGossipChannel blobSidecarGossipChannel;

  public BlockPublisherDeneb(
      final BlockFactory blockFactory,
      final BlockImportChannel blockImportChannel,
      final BlockGossipChannel blockGossipChannel,
      final BlobSidecarPool blobSidecarPool,
      final BlobSidecarGossipChannel blobSidecarGossipChannel,
      final PerformanceTracker performanceTracker,
      final DutyMetrics dutyMetrics) {
    super(blockFactory, blockImportChannel, performanceTracker, dutyMetrics);
    this.blobSidecarPool = blobSidecarPool;
    this.blockGossipChannel = blockGossipChannel;
    this.blobSidecarGossipChannel = blobSidecarGossipChannel;
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  @Override
  protected SafeFuture<BlockImportResult> gossipAndImportUnblindedSignedBlock(
      final SignedBlockContainer blockContainer) {
    final boolean delayed = gossipAndImportBlobSidecars(blockContainer);
    final SignedBeaconBlock block = blockContainer.getSignedBlock();


    if (delayed) {
      new SafeFuture<>()
          .orTimeout(500, TimeUnit.MILLISECONDS)
          .alwaysRun(
              () -> {
                System.out.println("*** delayed block publishing");
                blockGossipChannel.publishBlock(block);
              });
    } else {
      blockGossipChannel.publishBlock(block);
    }

    return blockImportChannel.importBlock(block);
  }

  private boolean gossipAndImportBlobSidecars(final SignedBlockContainer blockContainer) {
    final boolean[] delayed = new boolean[1];
    blockContainer
        .getSignedBlobSidecars()
        .ifPresent(
            signedBlobSidecars -> {
              delayed[0] =
                  publishSignedBlobSidecars(blockContainer.getSignedBlock(), signedBlobSidecars);
              blobSidecarPool.onCompletedBlockAndSignedBlobSidecars(
                  blockContainer.getSignedBlock(),
                  delayed[0]
                      ? signedBlobSidecars.subList(0, signedBlobSidecars.size() - 1)
                      : signedBlobSidecars);
            });
    return delayed[0];
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private boolean publishSignedBlobSidecars(
      final SignedBeaconBlock block, final List<SignedBlobSidecar> signedBlobSidecars) {
    if (signedBlobSidecars.isEmpty()) {
      return false;
    }
    if (signedBlobSidecars.size()
        == block
                .getBeaconBlock()
                .map(BeaconBlock::getBody)
                .flatMap(BeaconBlockBody::toVersionDeneb)
                .map(BeaconBlockBodyDeneb::getBlobKzgCommitments)
                .map(SszList::size)
                .orElseThrow()
            + 1) {

      final SignedBlobSidecar invalidSignedBlobSidecar =
          signedBlobSidecars.get(signedBlobSidecars.size() - 1);

      System.out.println(
          "*** publishing invalid blob sidecar first, index: "
              + invalidSignedBlobSidecar.getBlobSidecar().getIndex());
      // publish the last one first
      blobSidecarGossipChannel.publishBlobSidecars(
          List.of(signedBlobSidecars.get(signedBlobSidecars.size() - 1)));

      // publish the rest after a delay
      new SafeFuture<>()
          .orTimeout(500, TimeUnit.MILLISECONDS)
          .alwaysRun(
              () -> {
                System.out.println("*** publishing all valid blob sidecars");
                blobSidecarGossipChannel.publishBlobSidecars(
                    signedBlobSidecars.subList(0, signedBlobSidecars.size() - 1));
              });
      return true;
    }

    // publish all
    blobSidecarGossipChannel.publishBlobSidecars(signedBlobSidecars);
    return false;
  }
}
