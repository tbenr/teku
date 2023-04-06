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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.Cancellable;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.versions.deneb.BeaconBlockBodyDeneb;
import tech.pegasys.teku.spec.datastructures.execution.versions.deneb.BlobSidecar;
import tech.pegasys.teku.spec.datastructures.networking.libp2p.rpc.BlobIdentifier;
import tech.pegasys.teku.statetransition.blobs.BlockBlobsSidecarsTrackerTimingStrategy.TrackerTimings;

@SuppressWarnings("UnusedVariable")
public class BlockBlobSidecarsTracker {
  private static final Logger LOG = LogManager.getLogger();

  private final Spec spec;
  private final SlotAndBlockRoot slotAndBlockRoot;
  private final Optional<Cancellable> fetchTask;
  private final Optional<Cancellable> giveUpTask;

  private final AtomicReference<Optional<BeaconBlockBodyDeneb>> blockBody =
      new AtomicReference<>(Optional.empty());

  private final Map<UInt64, BlobSidecar> blobSidecars = new ConcurrentHashMap<>();
  private final SafeFuture<Void> blobSidecarsComplete = new SafeFuture<>();

  private BlockBlobSidecarsTracker(
      final AsyncRunner asyncRunner,
      final Spec spec,
      final SlotAndBlockRoot slotAndBlockRoot,
      final TrackerTimings timings,
      final BeaconBlockBodyDeneb blockBodyDeneb) {
    this.spec = spec;
    this.slotAndBlockRoot = slotAndBlockRoot;
    blockBody.set(Optional.of(blockBodyDeneb));

    this.fetchTask = createFetchTask(asyncRunner, timings, this);
    this.giveUpTask = createGiveUpTask(asyncRunner, timings, this);
  }

  private BlockBlobSidecarsTracker(
      final AsyncRunner asyncRunner,
      final Spec spec,
      final TrackerTimings timings,
      final BlobSidecar blobSidecar) {
    this.spec = spec;
    this.slotAndBlockRoot = blobSidecar.getSlotAndBlockRoot();
    blobSidecars.put(blobSidecar.getIndex(), blobSidecar);

    this.fetchTask = createFetchTask(asyncRunner, timings, this);
    this.giveUpTask = createGiveUpTask(asyncRunner, timings, this);
  }

  private static Optional<Cancellable> createFetchTask(
      final AsyncRunner asyncRunner,
      final TrackerTimings trackerTimings,
      final BlockBlobSidecarsTracker blockBlobSidecarsTracker) {
    return trackerTimings.fetchDelay.map(
        fetchDelay ->
            asyncRunner.runCancellableAfterDelay(
                blockBlobSidecarsTracker::onFetch,
                fetchDelay,
                blockBlobSidecarsTracker::onFetchException));
  }

  private static Optional<Cancellable> createGiveUpTask(
      final AsyncRunner asyncRunner,
      final TrackerTimings trackerTimings,
      final BlockBlobSidecarsTracker blockBlobSidecarsTracker) {
    return trackerTimings.giveUpDelay.map(
        giveUpDelay ->
            asyncRunner.runCancellableAfterDelay(
                blockBlobSidecarsTracker::onGiveUp,
                giveUpDelay,
                blockBlobSidecarsTracker::onGiveUpException));
  }

  public static BlockBlobSidecarsTracker createFrom(
      final SignedBeaconBlock block,
      final Spec spec,
      final AsyncRunner asyncRunner,
      final BlockBlobsSidecarsTrackerTimingStrategy blockBlobsSidecarsTrackerTimingStrategy) {

    final BeaconBlockBodyDeneb blockBodyDeneb =
        BeaconBlockBodyDeneb.required(block.getMessage().getBody());
    final SlotAndBlockRoot slotAndBlockRoot = block.getSlotAndBlockRoot();

    final TrackerTimings timings =
        blockBlobsSidecarsTrackerTimingStrategy.calculateTimings(slotAndBlockRoot.getSlot());

    return new BlockBlobSidecarsTracker(
        asyncRunner, spec, slotAndBlockRoot, timings, blockBodyDeneb);
  }

  public static BlockBlobSidecarsTracker createFrom(
      final BlobSidecar blobSidecar,
      final Spec spec,
      final AsyncRunner asyncRunner,
      final BlockBlobsSidecarsTrackerTimingStrategy blockBlobsSidecarsTrackerTimingStrategy) {

    final TrackerTimings timings =
        blockBlobsSidecarsTrackerTimingStrategy.calculateTimings(blobSidecar.getSlot());

    return new BlockBlobSidecarsTracker(asyncRunner, spec, timings, blobSidecar);
  }

  public Map<UInt64, BlobSidecar> getBlobSidecars() {
    return blobSidecars;
  }

  public SafeFuture<Void> getCompletionFuture() {
    if (giveUpTask.isPresent()) {
      return blobSidecarsComplete;
    }

    // the tracker is configured to immediately try to resolve or fail
    // this mode is used while syncing where we expect blobs are being pushed in the pool
    // all together and then blocks are imported straight away
    onGiveUp();

    return blobSidecarsComplete;
  }

  public Optional<BeaconBlockBodyDeneb> getBlockBody() {
    return blockBody.get();
  }

  public boolean containsBlobSidecar(final BlobIdentifier blobIdentifier) {
    return Optional.ofNullable(blobSidecars.get(blobIdentifier.getIndex()))
        .map(blobSidecar -> blobSidecar.getBlockRoot().equals(blobIdentifier.getBlockRoot()))
        .orElse(false);
  }

  public Stream<BlobIdentifier> getMissingBlobSidecars() {
    blockBody.get();
    if (blockBody.get().isEmpty()) {
      // TODO: block is still unknown.
      //  Should we return all potential maxBlobsPerBlock BlobIdentifiers?
      return Stream.of();
    }

    return UInt64.range(
            UInt64.ZERO, UInt64.valueOf(blockBody.get().get().getBlobKzgCommitments().size()))
        .filter(blobIndex -> !blobSidecars.containsKey(blobIndex))
        .map(blobIndex -> new BlobIdentifier(slotAndBlockRoot.getBlockRoot(), blobIndex));
  }

  public boolean add(final BlobSidecar blobSidecar) {
    checkArgument(
        blobSidecar.getBlockRoot().equals(slotAndBlockRoot.getBlockRoot()),
        "Wrong blobSidecar block root");

    if (blobSidecarsComplete.isDone()) {
      // already completed
      return false;
    }

    boolean addedNew = blobSidecars.put(blobSidecar.getIndex(), blobSidecar) == null;

    if (addedNew) {
      LOG.warn(
          "Multiple BlobSidecars with index {} for {} detected.",
          slotAndBlockRoot.toLogString(),
          blobSidecar.getIndex());
    }

    checkCompletion();

    return addedNew;
  }

  public int blobSidecarsCount() {
    return blobSidecars.size();
  }

  public void setBlock(final SignedBeaconBlock block) {
    checkArgument(block.getRoot().equals(slotAndBlockRoot.getBlockRoot()), "Wrong block");
    final Optional<BeaconBlockBodyDeneb> oldBlock =
        blockBody.getAndSet(
            Optional.of(BeaconBlockBodyDeneb.required(block.getMessage().getBody())));
    if (oldBlock.isPresent()) {
      LOG.debug("block was already set!");
      return;
    }

    checkCompletion();
  }

  public SlotAndBlockRoot getSlotAndBlockRoot() {
    return slotAndBlockRoot;
  }

  private void onFetch() {}

  private void onFetchException(final Throwable error) {
    LOG.error("An error occurred in fetching task", error);
    blobSidecarsComplete.completeExceptionally(
        new RuntimeException("An error occurred in fetching task", error));
  }

  private void onGiveUp() {
    checkCompletion();

    if (!blobSidecarsComplete.isDone()) {
      blobSidecarsComplete.cancel(false);
    }
  }

  private void onGiveUpException(final Throwable error) {
    LOG.error("An error occurred while giving up", error);
    blobSidecarsComplete.completeExceptionally(
        new RuntimeException("An error occurred while giving up", error));
  }

  private void checkCompletion() {
    if (blobSidecarsComplete.isDone()) {
      return;
    }
    if (areBlobsComplete()) {
      fetchTask.ifPresent(Cancellable::cancel);
      giveUpTask.ifPresent(Cancellable::cancel);
      blobSidecarsComplete.complete(null);
    }
  }

  private boolean areBlobsComplete() {
    return blockBody
        .get()
        .map(b -> blobSidecars.size() >= b.getBlobKzgCommitments().size())
        .orElse(false);
  }
}
