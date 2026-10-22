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

package tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.SupernodeEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffCodec;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffDag.ChainLink;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffDag.NodeReader;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffMetrics.SnapshotReason;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffMetrics.WritePhaseNanos;

/**
 * The state diff DAG changes made within one {@link KvStoreCombinedDao.HotUpdater} transaction.
 *
 * <p>Node records are written to the updater immediately and mirrored in memory, so later
 * operations of the same transaction see them although the DAO only reads committed data. Supernode
 * reference count changes are accumulated in memory per hash and written once by {@link #commit()},
 * which must be called before the updater itself is committed. If any operation, {@link #commit()}
 * included, throws, the updater holds a partial change set and must be discarded (closed without
 * committing), together with this transaction.
 *
 * <p>A node references each distinct supernode hash of its payload once, so a supernode's count is
 * the number of stored nodes whose payload references it.
 */
public class StateDiffDagTransaction {

  private static final Logger LOG = LogManager.getLogger();

  private final StateDiffDag dag;
  private final KvStoreCombinedDao dao;
  private final KvStoreCombinedDao.HotUpdater updater;
  private final NodeReader reader;

  /** Node records written (present) or deleted (empty) in this transaction. */
  private final Map<Bytes32, Optional<Bytes>> pendingNodes = new HashMap<>();

  /** SSZ of the supernodes referenced by nodes written in this transaction. */
  private final Map<Bytes32, Bytes> pendingSupernodes = new HashMap<>();

  /** Net reference count change per supernode hash, in first-touched order. */
  private final Map<Bytes32, Long> refCountDeltas = new LinkedHashMap<>();

  /**
   * Nodes written in this transaction by root, in the order of their latest write. Their metrics
   * and base cache entries are recorded by {@link #commit()}, so a failed transaction neither
   * counts nor caches them. A node written twice, as an anchor created late and then repaired, is
   * recorded once, as last written.
   */
  private final Map<Bytes32, WrittenNode> writtenNodes = new LinkedHashMap<>();

  /** State trees of the nodes written in this transaction, the diff bases of later nodes in it. */
  private final Map<Bytes32, TreeNode> writtenTrees = new HashMap<>();

  private boolean committed;

  StateDiffDagTransaction(
      final StateDiffDag dag,
      final KvStoreCombinedDao dao,
      final KvStoreCombinedDao.HotUpdater updater) {
    this.dag = dag;
    this.dao = dao;
    this.updater = updater;
    this.reader =
        new NodeReader() {
          @Override
          public Optional<Bytes> getEncodedNode(final Bytes32 blockRoot) {
            final Optional<Bytes> pending = pendingNodes.get(blockRoot);
            // A map entry holding Optional.empty() records a deletion
            if (pendingNodes.containsKey(blockRoot)) {
              return pending;
            }
            return dao.getStateDiffNode(blockRoot);
          }

          @Override
          public Optional<Bytes> load(final Bytes32 hash) {
            final Bytes pending = pendingSupernodes.get(hash);
            return pending != null ? Optional.of(pending) : dao.getStateSupernode(hash);
          }
        };
  }

  /**
   * Writes the node of the checkpoint block {@code blockRoot}, whose post-state is {@code state},
   * serving epochs {@code firstEpoch..lastEpoch}.
   *
   * <p>If a node for {@code blockRoot} already exists only its last epoch is raised, when {@code
   * lastEpoch} is greater. Otherwise the node's level is the coarsest level of its served epochs;
   * its previous node is the first of {@code ancestorCheckpointRoots} that has a node; its parent
   * is the nearest node reached from the previous node (inclusive) through parent pointers that is
   * a snapshot or has a level at least as coarse. With {@code forceSnapshot}, at the coarsest level
   * (a periodic snapshot, which bounds every reconstruction chain), without such a parent, or when
   * the parent's state is from another milestone, the node is a snapshot.
   *
   * @param ancestorCheckpointRoots checkpoint block roots of the ancestors, nearest first
   */
  public void writeNode(
      final Bytes32 blockRoot,
      final BeaconState state,
      final UInt64 firstEpoch,
      final UInt64 lastEpoch,
      final List<Bytes32> ancestorCheckpointRoots,
      final boolean forceSnapshot) {
    checkNotCommitted();
    checkArgument(
        firstEpoch.isLessThanOrEqualTo(lastEpoch),
        "First epoch %s is after last epoch %s",
        firstEpoch,
        lastEpoch);
    writeNode(
        blockRoot,
        state,
        firstEpoch,
        lastEpoch,
        dag.levels().maxLevelInRange(firstEpoch, lastEpoch),
        ancestorCheckpointRoots,
        forceSnapshot);
  }

  private void writeNode(
      final Bytes32 blockRoot,
      final BeaconState state,
      final UInt64 firstEpoch,
      final UInt64 lastEpoch,
      final int level,
      final List<Bytes32> ancestorCheckpointRoots,
      final boolean forceSnapshot) {
    final Optional<Bytes> existing = reader.getEncodedNode(blockRoot);
    if (existing.isPresent()) {
      final StateDiffNodeRecord record = NodeReader.decode(blockRoot, existing.get());
      if (lastEpoch.isGreaterThan(record.lastEpoch())) {
        putNode(blockRoot, StateDiffNodeRecordCodec.patchLastEpoch(existing.get(), lastEpoch));
      }
      return;
    }

    final long start = System.nanoTime();
    final boolean periodic = dag.levels().isCoarsest(level);
    final Optional<ChainLink> prev = findPrev(ancestorCheckpointRoots);
    // A snapshot needs no parent, so none is resolved for it
    final Optional<ChainLink> parent =
        forceSnapshot || periodic
            ? Optional.empty()
            : prev.flatMap(link -> resolveParent(link, level));
    final Optional<SnapshotReason> snapshotReason;
    if (forceSnapshot) {
      snapshotReason = Optional.of(SnapshotReason.FORCED);
    } else if (periodic) {
      snapshotReason = Optional.of(SnapshotReason.PERIODIC);
    } else if (parent.isEmpty()) {
      snapshotReason = Optional.of(SnapshotReason.NO_PARENT);
      if (!ancestorCheckpointRoots.isEmpty()) {
        LOG.warn(
            "Writing state diff node {} at epochs {}..{} as a snapshot: no parent node could be"
                + " resolved from its {} ancestor checkpoint roots (previous node {})",
            blockRoot,
            firstEpoch,
            lastEpoch,
            ancestorCheckpointRoots.size(),
            prev.map(link -> link.root().toHexString()).orElse("none"));
      }
    } else if (!dag.support().sameMilestone(state.getSlot(), parent.get().record().blockSlot())) {
      snapshotReason = Optional.of(SnapshotReason.MILESTONE);
    } else {
      snapshotReason = Optional.empty();
    }
    final boolean snapshot = snapshotReason.isPresent();

    final TreeNode base =
        snapshot
            ? dag.support().schemaFor(state.getSlot()).getDefaultTree()
            : diffBase(level, parent.get());
    final long walkStart = System.nanoTime();
    final Map<Bytes32, Bytes> referencedSupernodes = new LinkedHashMap<>();
    final TreeDiff diff =
        dag.support()
            .differFor(state.getSlot())
            .diff(base, state.getBackingNode(), referencedSupernodes::putIfAbsent);
    final long encodeStart = System.nanoTime();
    final Bytes payload = TreeDiffCodec.serialize(diff);

    final long storeStart = System.nanoTime();
    final StateDiffNodeRecord record =
        new StateDiffNodeRecord(
            state.getSlot(),
            state.hashTreeRoot(),
            firstEpoch,
            lastEpoch,
            level,
            snapshot,
            prev.map(ChainLink::root).orElse(Bytes32.ZERO),
            snapshot ? Bytes32.ZERO : parent.get().root(),
            payload);
    final Bytes encoded = StateDiffNodeRecordCodec.encode(record);
    putNode(blockRoot, encoded);
    referencedSupernodes.forEach(
        (hash, ssz) -> {
          pendingSupernodes.putIfAbsent(hash, ssz);
          addRefCountDelta(hash, 1);
        });

    final long end = System.nanoTime();

    writtenTrees.put(blockRoot, state.getBackingNode());
    // Removed first so that a rewritten node moves to the end of the write order
    writtenNodes.remove(blockRoot);
    writtenNodes.put(
        blockRoot,
        new WrittenNode(
            blockRoot,
            state,
            record,
            snapshotReason,
            encoded.size(),
            DiffSectionSizes.of(diff),
            new WritePhaseNanos(
                walkStart - start,
                encodeStart - walkStart,
                storeStart - encodeStart,
                end - storeStart)));
  }

  /**
   * Writes the node of {@code blockRoot} serving only {@code epoch}, unless it already exists.
   *
   * <p>A node written here is written late, after nodes of later epochs may already diff against
   * its ancestors. It is therefore written at the finest level whatever its epoch: its parent is
   * its previous node, so its reconstruction chain contains the previous node's and retention keeps
   * every node a later node may depend on. Coarse nodes written later walk past it through parent
   * pointers.
   *
   * @return whether the node was written
   */
  public boolean ensureNode(
      final Bytes32 blockRoot,
      final BeaconState state,
      final UInt64 epoch,
      final List<Bytes32> ancestorCheckpointRoots) {
    checkNotCommitted();
    if (reader.getEncodedNode(blockRoot).isPresent()) {
      return false;
    }
    writeNode(
        blockRoot,
        state,
        epoch,
        epoch,
        dag.levels().levelCount() - 1,
        ancestorCheckpointRoots,
        false);
    return true;
  }

  /**
   * Rewrites the node of the finalized anchor {@code anchorRoot} as a snapshot of {@code state},
   * then deletes the finalized-chain nodes the anchor no longer needs, as {@link
   * #retainFinalizedChain} does but reading only the part of the previous anchor's chain that can
   * still be read. For an anchor whose chain, or the previous anchor's, cannot be loaded.
   *
   * <p>Nodes above the anchor whose chains led through deleted or unreadable nodes stay broken:
   * reading them fails and their states are regenerated by replay instead, and each is repaired in
   * turn if it becomes the anchor. Undecodable records are deleted without releasing their
   * supernode references.
   *
   * @param anchorEpoch the epoch served by the anchor node when it has no readable node
   */
  public void repairAnchor(
      final Optional<Bytes32> previousAnchorRoot,
      final Bytes32 anchorRoot,
      final BeaconState state,
      final UInt64 anchorEpoch,
      final Collection<Bytes32> newlyFinalizedRoots,
      final boolean retainAll) {
    checkNotCommitted();
    UInt64 firstEpoch = anchorEpoch;
    UInt64 lastEpoch = anchorEpoch;
    try {
      final Optional<StateDiffNodeRecord> existing = reader.getNode(anchorRoot);
      if (existing.isPresent()) {
        firstEpoch = existing.get().firstEpoch();
        lastEpoch = existing.get().lastEpoch();
      }
    } catch (final StateDiffVerificationException e) {
      LOG.debug("Anchor node {} is unreadable, rewriting it for epoch {}", anchorRoot, anchorEpoch);
    }
    // Overwritten rather than deleted: a delete and a put of one key in one batch is not ordered
    // by every store
    if (reader.getEncodedNode(anchorRoot).isPresent()) {
      releaseReferencesTolerantly(anchorRoot);
      pendingNodes.put(anchorRoot, Optional.empty());
    }
    writeNode(anchorRoot, state, firstEpoch, lastEpoch, List.of(), true);
    if (retainAll) {
      return;
    }
    final Set<Bytes32> candidates = new LinkedHashSet<>();
    previousAnchorRoot.ifPresent(
        root -> dag.loadChainPrefix(reader, root).forEach(link -> candidates.add(link.root())));
    candidates.addAll(newlyFinalizedRoots);
    candidates.remove(anchorRoot);
    deleteNodesTolerantly(candidates);
  }

  /**
   * Deletes the nodes stored for {@code blockRoots}, releasing their supernode references. Roots
   * without a node are ignored.
   *
   * <p>A node whose record or payload cannot be decoded is deleted without releasing its supernode
   * references (counted as {@code deleted_corrupt} and logged at ERROR) rather than failing the
   * transaction: a pruned node is garbage either way, and throwing would fail every retry of the
   * storage update that prunes it.
   */
  public void deleteNodes(final Collection<Bytes32> blockRoots) {
    checkNotCommitted();
    deleteNodesTolerantly(blockRoots);
  }

  /**
   * Deletes the nodes stored for {@code blockRoots}, releasing their supernode references, but
   * deletes a node whose record or payload cannot be decoded without releasing them, counting it as
   * {@code deleted_corrupt}. Its supernodes keep a reference too many: a leak, never a missing
   * supernode.
   */
  private void deleteNodesTolerantly(final Collection<Bytes32> blockRoots) {
    for (final Bytes32 blockRoot : blockRoots) {
      if (reader.getEncodedNode(blockRoot).isEmpty()) {
        continue;
      }
      final boolean released = releaseReferencesTolerantly(blockRoot);
      pendingNodes.put(blockRoot, Optional.empty());
      updater.deleteStateDiffNode(blockRoot);
      if (released) {
        dag.metrics().onNodeDeleted();
      } else {
        dag.metrics().onNodeDeletedCorrupt();
      }
    }
  }

  /**
   * Releases the supernode references of a stored node, unless it cannot be decoded.
   *
   * @return whether the references were released
   */
  private boolean releaseReferencesTolerantly(final Bytes32 blockRoot) {
    try {
      supernodeHashes(blockRoot, reader.getNode(blockRoot).orElseThrow())
          .forEach(hash -> addRefCountDelta(hash, -1));
      return true;
    } catch (final StateDiffVerificationException e) {
      LOG.error(
          "Dropping unreadable state diff node {} without releasing its supernode references",
          blockRoot,
          e);
      return false;
    }
  }

  /**
   * Deletes the finalized-chain nodes no longer needed once the finalized anchor moves from {@code
   * previousAnchorRoot} to {@code newAnchorRoot}: the nodes of the previous anchor's reconstruction
   * chain, of the newly finalized blocks and of the new anchor, except those in the new anchor's
   * reconstruction chain.
   *
   * <p>The newly finalized blocks are given rather than found by following previous-node pointers
   * from the new anchor: a node created late for the anchor points straight at the previous anchor
   * and would hide the nodes written in between.
   *
   * <p>A candidate whose record or payload cannot be decoded is deleted without releasing its
   * supernode references (counted as {@code deleted_corrupt} and logged at ERROR): it is garbage
   * either way and a repair would not recover it. Only the chains of the two anchors must load.
   *
   * @param previousAnchorRoot the previous anchor; when empty there is no previous chain
   * @param newlyFinalizedRoots the roots of the blocks finalized by this anchor move; roots without
   *     a node are ignored
   * @param retainAll keep every finalized node (archive mode)
   * @throws StateDiffVerificationException when the chain of the previous or the new anchor cannot
   *     be loaded; nothing is deleted in that case
   */
  public void retainFinalizedChain(
      final Optional<Bytes32> previousAnchorRoot,
      final Bytes32 newAnchorRoot,
      final Collection<Bytes32> newlyFinalizedRoots,
      final boolean retainAll) {
    checkNotCommitted();
    if (retainAll) {
      return;
    }
    final Set<Bytes32> candidates = new LinkedHashSet<>();
    previousAnchorRoot.ifPresent(
        root -> dag.loadChain(reader, root).forEach(link -> candidates.add(link.root())));
    candidates.addAll(newlyFinalizedRoots);
    candidates.add(newAnchorRoot);

    final List<ChainLink> newChain = dag.loadChain(reader, newAnchorRoot);
    // Without the new anchor's node every candidate would be deleted, the previous chain included
    checkState(!newChain.isEmpty(), "No state diff node for the new anchor %s", newAnchorRoot);
    newChain.forEach(link -> candidates.remove(link.root()));
    deleteNodesTolerantly(candidates);
  }

  /**
   * Writes the accumulated supernode changes to the updater: inserts the SSZ of supernodes gaining
   * their first reference, updates counts, and deletes supernodes whose count reaches zero.
   *
   * @throws IllegalStateException when a decrement targets a missing count or would make a count
   *     negative; nothing is written in that case
   */
  public void commit() {
    checkNotCommitted();
    committed = true;
    final List<Runnable> writes = new ArrayList<>();
    refCountDeltas.forEach(
        (hash, delta) -> {
          if (delta == 0) {
            return;
          }
          final Optional<UInt64> current = dao.getStateSupernodeRefCount(hash);
          if (delta < 0 && current.isEmpty()) {
            throw new IllegalStateException(
                "Releasing " + -delta + " references of supernode " + hash + " without a count");
          }
          final long newCount = current.map(UInt64::longValue).orElse(0L) + delta;
          if (newCount < 0) {
            throw new IllegalStateException(
                "Releasing "
                    + -delta
                    + " references of supernode "
                    + hash
                    + " with count "
                    + current.get());
          }
          if (current.isEmpty()) {
            // The count is absent exactly when the bytes are; rewriting immutable,
            // content-addressed bytes would be harmless anyway
            final Bytes ssz = pendingSupernodes.get(hash);
            checkState(ssz != null, "No SSZ for new supernode %s", hash);
            writes.add(
                () -> {
                  updater.putStateSupernode(hash, ssz);
                  dag.metrics().onSupernodeInserted(ssz.size());
                });
          } else if (delta > 0) {
            writes.add(() -> dag.metrics().onSupernodeIncremented());
          }
          if (newCount == 0) {
            writes.add(
                () -> {
                  updater.deleteStateSupernode(hash);
                  dag.metrics().onSupernodeDeleted();
                });
          } else {
            writes.add(() -> updater.setStateSupernodeRefCount(hash, UInt64.valueOf(newCount)));
          }
        });
    writes.forEach(Runnable::run);
    writtenNodes.values().forEach(this::recordWritten);
  }

  private void recordWritten(final WrittenNode node) {
    final StateDiffNodeRecord record = node.record();
    // A node deleted later in this transaction is not cached. One rewritten, as by an anchor
    // repair, is recorded once as last written and has the same state either way
    if (pendingNodes.getOrDefault(node.blockRoot(), Optional.empty()).isPresent()) {
      dag.baseCache().put(cacheLevel(record), node.blockRoot(), node.state());
    }
    dag.metrics().onNodeWritten(record.level(), record.snapshot(), node.encodedBytes());
    node.snapshotReason().ifPresent(reason -> dag.metrics().onSnapshotWritten(reason));
    dag.metrics().onSections(node.sections());
    dag.metrics().onWrite(node.phaseNanos().total());
    dag.metrics().onWritePhases(node.phaseNanos());
    if (LOG.isDebugEnabled()) {
      final DiffSectionSizes sections = node.sections();
      LOG.debug(
          "Wrote state diff node {} for epochs {}..{} at level {} as {}: {} bytes, section bytes"
              + " leaves {}, supernodes {}, zeroed {}, copied {}, deltas {}",
          node.blockRoot(),
          record.firstEpoch(),
          record.lastEpoch(),
          record.level(),
          node.snapshotReason().map(reason -> "snapshot (" + reason.label() + ")").orElse("diff"),
          node.encodedBytes(),
          sections.leaves().bytes(),
          sections.supernodes().bytes(),
          sections.zeroed().bytes(),
          sections.copied().bytes(),
          sections.deltas().bytes());
    }
  }

  private void checkNotCommitted() {
    checkState(!committed, "State diff DAG transaction already committed");
  }

  private void putNode(final Bytes32 blockRoot, final Bytes encoded) {
    pendingNodes.put(blockRoot, Optional.of(encoded));
    updater.putStateDiffNode(blockRoot, encoded);
  }

  private void addRefCountDelta(final Bytes32 hash, final long delta) {
    refCountDeltas.merge(hash, delta, Long::sum);
  }

  private Optional<ChainLink> findPrev(final List<Bytes32> ancestorCheckpointRoots) {
    for (final Bytes32 root : ancestorCheckpointRoots) {
      final Optional<StateDiffNodeRecord> record = reader.getNode(root);
      if (record.isPresent()) {
        return Optional.of(new ChainLink(root, record.get()));
      }
    }
    return Optional.empty();
  }

  /**
   * Walks parent pointers from {@code prev} (inclusive) to the first node that can be the diff base
   * of a node at {@code level}: a snapshot or a node of that level or a coarser one.
   *
   * <p>Parent pointers, not previous-node pointers: a parent is the nearest earlier node at least
   * as coarse as its child, so the parent chain skips only nodes too fine to qualify and reaches
   * the same nearest qualifying ancestor. It also stays inside reconstruction chains, which
   * retention keeps, whereas the finer nodes between them are deleted and would dead-end a
   * previous-node walk. The walk ends at a snapshot, which always qualifies, so it fails only on a
   * missing parent node.
   */
  private Optional<ChainLink> resolveParent(final ChainLink prev, final int level) {
    ChainLink link = prev;
    for (int steps = 0; steps < StateDiffDag.MAX_WALK_LENGTH; steps++) {
      if (isParentCandidate(link.record(), level)) {
        return Optional.of(link);
      }
      final Bytes32 parentRoot = link.record().parentNodeRoot();
      final Optional<StateDiffNodeRecord> record = reader.getNode(parentRoot);
      if (record.isEmpty()) {
        return Optional.empty();
      }
      link = new ChainLink(parentRoot, record.get());
    }
    return Optional.empty();
  }

  private static boolean isParentCandidate(final StateDiffNodeRecord record, final int level) {
    return record.snapshot() || record.level() <= level;
  }

  /** A snapshot is a valid base for every level, so it is cached as the coarsest level. */
  private static int cacheLevel(final StateDiffNodeRecord record) {
    return record.snapshot() ? 0 : record.level();
  }

  private TreeNode diffBase(final int level, final ChainLink parent) {
    final Optional<TreeNode> cached =
        Optional.ofNullable(writtenTrees.get(parent.root()))
            .or(() -> dag.baseCache().get(level, parent.root()));
    if (cached.isPresent()) {
      dag.metrics().onBaseCacheHit();
      return cached.get();
    }
    dag.metrics().onBaseCacheMiss();
    final BeaconState parentState =
        dag.reconstruct(reader, parent.root())
            .orElseThrow(
                () -> new StateDiffVerificationException("Missing parent node " + parent.root()));
    dag.baseCache().put(cacheLevel(parent.record()), parent.root(), parentState);
    return parentState.getBackingNode();
  }

  /**
   * The distinct supernode hashes referenced by the payload of the node of {@code blockRoot}.
   *
   * @throws StateDiffVerificationException when the payload cannot be decoded
   */
  private static Set<Bytes32> supernodeHashes(
      final Bytes32 blockRoot, final StateDiffNodeRecord record) {
    final List<SupernodeEntry> supernodes;
    try {
      supernodes = TreeDiffCodec.deserialize(record.payload()).supernodes();
    } catch (final IllegalArgumentException e) {
      throw new StateDiffVerificationException(
          "Corrupt payload of state diff node " + blockRoot + ": " + e.getMessage(), e);
    }
    final Set<Bytes32> hashes = new LinkedHashSet<>();
    for (final SupernodeEntry entry : supernodes) {
      hashes.add(entry.hash());
    }
    return hashes;
  }

  private record WrittenNode(
      Bytes32 blockRoot,
      BeaconState state,
      StateDiffNodeRecord record,
      Optional<SnapshotReason> snapshotReason,
      int encodedBytes,
      DiffSectionSizes sections,
      WritePhaseNanos phaseNanos) {}
}
