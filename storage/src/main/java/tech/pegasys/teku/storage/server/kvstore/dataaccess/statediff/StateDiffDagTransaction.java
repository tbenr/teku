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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff;
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
 *
 * <p>Anchor recovery and finalized retention ({@link #onAnchorFinalized}) are delegated to a {@link
 * FinalizedChainMaintenance}, which changes nodes only through this transaction.
 */
public class StateDiffDagTransaction {

  private static final Logger LOG = LogManager.getLogger();

  private final StateDiffDag dag;
  private final KvStoreCombinedDao dao;
  private final KvStoreCombinedDao.HotUpdater updater;
  private final NodeReader reader;
  private final FinalizedChainMaintenance maintenance;

  /** Node records written (present) or deleted (empty) in this transaction. */
  private final Map<Bytes32, Optional<Bytes>> pendingNodes = new HashMap<>();

  /**
   * Node records read and decoded in this transaction, absent ones included, so that a record of
   * several MB is read and decoded once however many operations of the transaction visit it. An
   * entry is dropped when this transaction writes or deletes its node.
   */
  private final Map<Bytes32, Optional<StateDiffNodeRecord>> decodedNodes = new HashMap<>();

  /** Committed records read in this transaction, absent ones included, by block root. */
  private final Map<Bytes32, Optional<Bytes>> committedNodes = new HashMap<>();

  /** SSZ of the supernodes referenced by nodes written in this transaction. */
  // Content suppliers, so a frozen supernode is loaded only when its content is needed
  private final Map<Bytes32, Supplier<Bytes>> pendingSupernodes = new HashMap<>();

  /** Net reference count change per supernode hash, in first-touched order. */
  private final Map<Bytes32, Long> refCountDeltas = new LinkedHashMap<>();

  /**
   * Nodes written in this transaction by root, in the order of their latest write. Their metrics
   * and base cache entries are recorded by {@link #commit()}, so a failed transaction neither
   * counts nor caches them. A node written twice, as an anchor created late and then repaired, is
   * recorded once, as last written.
   */
  private final Map<Bytes32, WrittenNode> writtenNodes = new LinkedHashMap<>();

  /**
   * State trees of the nodes written in this transaction: the diff bases of later nodes in it and
   * the start of the reconstructions through them.
   */
  private final Map<Bytes32, TreeNode> writtenTrees = new HashMap<>();

  /** The window start recorded in this transaction, published to the metrics on commit. */
  private Optional<UInt64> windowStart = Optional.empty();

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
            return committedNodes.computeIfAbsent(blockRoot, dao::getStateDiffNode);
          }

          @Override
          public Optional<StateDiffNodeRecord> getNode(final Bytes32 blockRoot) {
            final Optional<StateDiffNodeRecord> decoded = decodedNodes.get(blockRoot);
            if (decoded != null) {
              return decoded;
            }
            // Not memoized when decoding throws, so a later read fails the same way
            final Optional<StateDiffNodeRecord> record =
                getEncodedNode(blockRoot).map(encoded -> NodeReader.decode(blockRoot, encoded));
            decodedNodes.put(blockRoot, record);
            return record;
          }

          @Override
          public Optional<Bytes> load(final Bytes32 hash) {
            final Supplier<Bytes> pending = pendingSupernodes.get(hash);
            return pending != null ? Optional.of(pending.get()) : dao.getStateSupernode(hash);
          }

          @Override
          public Map<Bytes32, Bytes> loadAll(final Collection<Bytes32> hashes) {
            final Map<Bytes32, Bytes> loaded = new HashMap<>();
            final List<Bytes32> committed = new ArrayList<>();
            for (final Bytes32 hash : hashes) {
              final Supplier<Bytes> pending = pendingSupernodes.get(hash);
              if (pending != null) {
                loaded.put(hash, pending.get());
              } else {
                committed.add(hash);
              }
            }
            if (!committed.isEmpty()) {
              loaded.putAll(dao.getStateSupernodes(committed));
            }
            return loaded;
          }
        };
    this.maintenance = new FinalizedChainMaintenance(dag, this);
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

  /**
   * Writes the node of the checkpoint block {@code blockRoot} as {@link #writeNode} does without
   * forcing a snapshot, but writes it as a snapshot, logging at ERROR, when its diff base, an
   * earlier node, cannot be reconstructed. Failing instead would fail block import on every retry;
   * a snapshot needs no base and later nodes chain to it.
   *
   * @param ancestorCheckpointRoots checkpoint block roots of the ancestors, nearest first
   */
  public void writeNodeOrSnapshot(
      final Bytes32 blockRoot,
      final BeaconState state,
      final UInt64 firstEpoch,
      final UInt64 lastEpoch,
      final List<Bytes32> ancestorCheckpointRoots) {
    try {
      writeNode(blockRoot, state, firstEpoch, lastEpoch, ancestorCheckpointRoots, false);
    } catch (final StateDiffVerificationException e) {
      // writeNode reads everything it needs before it writes, so nothing of the failed attempt is
      // in the transaction
      LOG.error(
          "Cannot reconstruct the diff base of the state diff node of block {} at slot {}; writing"
              + " it as a snapshot",
          blockRoot,
          state.getSlot(),
          e);
      writeNode(blockRoot, state, firstEpoch, lastEpoch, ancestorCheckpointRoots, true);
    }
  }

  /**
   * Writes the node of {@code blockRoot} at {@code level}, or raises its last epoch when it already
   * exists, as {@link #writeNode(Bytes32, BeaconState, UInt64, UInt64, List, boolean)} does.
   */
  void writeNode(
      final Bytes32 blockRoot,
      final BeaconState state,
      final UInt64 firstEpoch,
      final UInt64 lastEpoch,
      final int level,
      final List<Bytes32> ancestorCheckpointRoots,
      final boolean forceSnapshot) {
    final Optional<StateDiffNodeRecord> existing = reader.getNode(blockRoot);
    if (existing.isPresent()) {
      if (lastEpoch.isGreaterThan(existing.get().lastEpoch())) {
        putNode(
            blockRoot,
            StateDiffNodeRecordCodec.patchLastEpoch(
                reader.getEncodedNode(blockRoot).orElseThrow(), lastEpoch));
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
            : diffBase(parent.get());
    final long walkStart = System.nanoTime();
    final Map<Bytes32, Supplier<Bytes>> referencedSupernodes = new LinkedHashMap<>();
    final TreeDiff diff =
        dag.support()
            .differFor(state.getSlot())
            .diffLazy(
                base,
                state.getBackingNode(),
                (hash, content) ->
                    referencedSupernodes.putIfAbsent(hash, Suppliers.memoize(content::get)));
    final long encodeStart = System.nanoTime();
    final TreeDiffCodec.Encoded encodedDiff = StateDiffNodeRecordCodec.encodePayload(diff);

    final long storeStart = System.nanoTime();
    final StateDiffNodeRecord record =
        new StateDiffNodeRecord(
            state.getSlot(),
            state.hashTreeRoot(),
            firstEpoch,
            lastEpoch,
            level,
            snapshot,
            snapshot ? Bytes32.ZERO : parent.get().root(),
            encodedDiff.payload());
    final Bytes encoded = StateDiffNodeRecordCodec.encode(record, encodedDiff);
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
            DiffSectionSizes.of(diff, encodedDiff.sectionBytes()),
            new WritePhaseNanos(
                walkStart - start,
                encodeStart - walkStart,
                storeStart - encodeStart,
                end - storeStart)));
  }

  /**
   * Moves the finalized anchor from {@code previousAnchorRoot} to {@code anchorRoot}: ensures the
   * anchor has a node (see {@link FinalizedChainMaintenance#ensureNode}), then deletes the
   * finalized-chain nodes it no longer needs (see {@link
   * FinalizedChainMaintenance#retainFinalizedChain}).
   *
   * <p>When either step cannot load a chain, the anchor is repaired instead of failing the
   * transaction, which would fail every retry and every restart: the failed step is logged at
   * ERROR, the anchor node is rewritten as a snapshot of {@code anchorState}, overwriting a node
   * {@code ensureNode} may have written, and retention runs against it reading only the part of the
   * previous anchor's chain that can still be read. Each node change is complete before the next
   * one is read, so the transaction is consistent when the repair starts. Nodes above the anchor
   * whose chains led through deleted or unreadable nodes stay broken: reading them fails, their
   * states are regenerated by replay instead, and each is repaired in turn if it becomes the
   * anchor. Nodes below a lost node can no longer be reached and stay on disk. Retaining an archive
   * window loads no anchor chain and never fails (see {@link FinalizedChainMaintenance}), so only a
   * failed ensure step repairs the anchor there.
   *
   * <p>Counts {@code nodes_total{state=anchor_created_late}} when the anchor node is created here
   * and {@code nodes_total{state=anchor_repaired}} when it is repaired.
   *
   * @param previousAnchorRoot the anchor being replaced; when empty there is no previous chain
   * @param anchorEpoch the epoch served by the anchor node when it has to be written
   * @param finalizedAncestorRoots the anchor's ancestors finalized by this move, nearest first,
   *     followed by the previous anchor: the ancestors a node created late is chained through, so
   *     that it keeps the nodes written between the two anchors that later nodes may depend on
   * @param newlyFinalizedRoots the roots of the blocks finalized by this anchor move; roots without
   *     a node are ignored
   * @param retention which finalized nodes to keep
   */
  public void onAnchorFinalized(
      final Optional<Bytes32> previousAnchorRoot,
      final Bytes32 anchorRoot,
      final BeaconState anchorState,
      final UInt64 anchorEpoch,
      final List<Bytes32> finalizedAncestorRoots,
      final Collection<Bytes32> newlyFinalizedRoots,
      final FinalizedRetention retention) {
    checkNotCommitted();
    maintenance.onAnchorFinalized(
        previousAnchorRoot,
        anchorRoot,
        anchorState,
        anchorEpoch,
        finalizedAncestorRoots,
        newlyFinalizedRoots,
        retention);
  }

  /** See {@link FinalizedChainMaintenance#ensureNode}. */
  @VisibleForTesting
  boolean ensureNode(
      final Bytes32 blockRoot,
      final BeaconState state,
      final UInt64 epoch,
      final List<Bytes32> ancestorCheckpointRoots) {
    return maintenance.ensureNode(blockRoot, state, epoch, ancestorCheckpointRoots);
  }

  /**
   * Deletes the nodes stored for {@code blockRoots}, releasing their supernode references. Roots
   * without a node are ignored.
   *
   * <p>A node whose record or payload cannot be decoded is deleted without releasing its supernode
   * references (counted as {@code deleted_corrupt} and logged at ERROR) rather than failing the
   * transaction: a pruned node is garbage either way, and throwing would fail every retry of the
   * storage update that prunes it. Its supernodes keep a reference too many: a leak, never a
   * missing supernode.
   */
  public void deleteNodes(final Collection<Bytes32> blockRoots) {
    checkNotCommitted();
    for (final Bytes32 blockRoot : blockRoots) {
      if (reader.getEncodedNode(blockRoot).isEmpty()) {
        continue;
      }
      final boolean released = releaseReferencesTolerantly(blockRoot);
      setPendingNode(blockRoot, Optional.empty());
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

  /** See {@link FinalizedChainMaintenance#retainFinalizedChain}. */
  @VisibleForTesting
  void retainFinalizedChain(
      final Optional<Bytes32> previousAnchorRoot,
      final Bytes32 newAnchorRoot,
      final Collection<Bytes32> newlyFinalizedRoots,
      final FinalizedRetention retention) {
    maintenance.retainFinalizedChain(
        previousAnchorRoot, newAnchorRoot, newlyFinalizedRoots, retention, false);
  }

  /**
   * Rewrites the node of {@code blockRoot} as a snapshot of {@code state} serving {@code
   * firstEpoch..lastEpoch}, at the coarsest level of those epochs. The supernode references of the
   * node it replaces are released, unless that node cannot be decoded (logged at ERROR).
   */
  void replaceNodeWithSnapshot(
      final Bytes32 blockRoot,
      final BeaconState state,
      final UInt64 firstEpoch,
      final UInt64 lastEpoch) {
    if (reader.getEncodedNode(blockRoot).isPresent()) {
      releaseReferencesTolerantly(blockRoot);
      // Forgotten rather than deleted, since it is put again below: a delete and a put of one key
      // in one batch is not ordered by every store
      setPendingNode(blockRoot, Optional.empty());
    }
    writeNode(
        blockRoot,
        state,
        firstEpoch,
        lastEpoch,
        dag.levels().maxLevelInRange(firstEpoch, lastEpoch),
        List.of(),
        true);
  }

  /**
   * Records {@code epoch} as the first epoch whose finalized state the archive database serves from
   * the DAG; see {@link KvStoreCombinedDao#getStateDiffWindowStart()}.
   */
  public void setWindowStart(final UInt64 epoch) {
    checkNotCommitted();
    updater.setStateDiffWindowStart(epoch);
    windowStart = Optional.of(epoch);
  }

  /** Removes the window start, which only an archive database has. */
  public void clearWindowStart() {
    checkNotCommitted();
    updater.deleteStateDiffWindowStart();
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
    final List<Bytes32> touchedHashes = new ArrayList<>();
    refCountDeltas.forEach(
        (hash, delta) -> {
          if (delta != 0) {
            touchedHashes.add(hash);
          }
        });
    final Map<Bytes32, UInt64> currentCounts = dao.getStateSupernodeRefCounts(touchedHashes);
    final List<Runnable> writes = new ArrayList<>();
    refCountDeltas.forEach(
        (hash, delta) -> {
          if (delta == 0) {
            return;
          }
          final Optional<UInt64> current = Optional.ofNullable(currentCounts.get(hash));
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
            final Supplier<Bytes> content = pendingSupernodes.get(hash);
            checkState(content != null, "No SSZ for new supernode %s", hash);
            final Bytes ssz = content.get();
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
    windowStart.ifPresent(dag.metrics()::onWindowStart);
  }

  private void recordWritten(final WrittenNode node) {
    final StateDiffNodeRecord record = node.record();
    // A node deleted later in this transaction is not cached. One rewritten, as by an anchor
    // repair, is recorded once as last written and has the same state either way
    if (pendingNodes.getOrDefault(node.blockRoot(), Optional.empty()).isPresent()) {
      dag.baseCache().put(LevelBaseCache.cacheLevel(record), node.blockRoot(), node.state());
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
              + " leaves {}, supernodes {}, patches {}, zeroed {}, copied {}, deltas {} ({}"
              + " compressed)",
          node.blockRoot(),
          record.firstEpoch(),
          record.lastEpoch(),
          record.level(),
          node.snapshotReason().map(reason -> "snapshot (" + reason.label() + ")").orElse("diff"),
          node.encodedBytes(),
          sections.leaves().bytes(),
          sections.supernodes().bytes(),
          sections.patches().bytes(),
          sections.zeroed().bytes(),
          sections.copied().bytes(),
          sections.deltas().bytes(),
          sections.deltasCompressed().bytes());
    }
  }

  private void checkNotCommitted() {
    checkState(!committed, "State diff DAG transaction already committed");
  }

  private void putNode(final Bytes32 blockRoot, final Bytes encoded) {
    setPendingNode(blockRoot, Optional.of(encoded));
    updater.putStateDiffNode(blockRoot, encoded);
  }

  /** Records a write (present) or deletion (empty) of a node, superseding any record read. */
  private void setPendingNode(final Bytes32 blockRoot, final Optional<Bytes> encoded) {
    pendingNodes.put(blockRoot, encoded);
    decodedNodes.remove(blockRoot);
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

  /**
   * The state tree of {@code parent}: written in this transaction, cached, or else reconstructed
   * from the nearest node of its chain that is either, and then cached.
   */
  private TreeNode diffBase(final ChainLink parent) {
    final Optional<TreeNode> cached =
        writtenTree(parent.root()).or(() -> dag.baseCache().find(parent.root()));
    if (cached.isPresent()) {
      dag.metrics().onBaseCacheHit();
      return cached.get();
    }
    dag.metrics().onBaseCacheMiss();
    final BeaconState parentState =
        dag.reconstruct(reader, parent.root(), this::writtenTree)
            .orElseThrow(
                () -> new StateDiffVerificationException("Missing parent node " + parent.root()));
    dag.baseCache().put(LevelBaseCache.cacheLevel(parent.record()), parent.root(), parentState);
    return parentState.getBackingNode();
  }

  private Optional<TreeNode> writtenTree(final Bytes32 blockRoot) {
    return Optional.ofNullable(writtenTrees.get(blockRoot));
  }

  /**
   * The distinct supernode hashes referenced by the payload of the node of {@code blockRoot}.
   *
   * @throws StateDiffVerificationException when the payload cannot be decoded
   */
  private static Set<Bytes32> supernodeHashes(
      final Bytes32 blockRoot, final StateDiffNodeRecord record) {
    try {
      return new LinkedHashSet<>(TreeDiffCodec.supernodeHashes(record.payload()));
    } catch (final IllegalArgumentException e) {
      throw new StateDiffVerificationException(
          "Corrupt payload of state diff node " + blockRoot + ": " + e.getMessage(), e);
    }
  }

  /** Reads nodes and supernodes as this transaction sees them, its own writes included. */
  NodeReader reader() {
    return reader;
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
