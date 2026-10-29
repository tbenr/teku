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

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.DoubleAdder;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.Histogram;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import org.hyperledger.besu.plugin.services.metrics.LabelledSuppliedMetric;
import tech.pegasys.teku.infrastructure.metrics.SettableLabelledGauge;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/** Metrics of the state diff DAG, all under {@link TekuMetricCategory#STORAGE}. */
class StateDiffMetrics {

  static final String SNAPSHOT_LEVEL_LABEL = "snapshot";

  /** The section label of the compressed delta bytes, which has no entry count of its own. */
  private static final String DELTAS_COMPRESSED_SECTION = "deltas_compressed";

  private static final double[] SECONDS_BUCKETS = {
    0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0
  };
  private static final double[] BYTES_BUCKETS = {
    1_024, 16_384, 65_536, 262_144, 1_048_576, 4_194_304, 16_777_216, 67_108_864, 268_435_456
  };
  private static final double[] CHAIN_LENGTH_BUCKETS = {1, 2, 4, 8, 12, 16, 24, 32, 64};

  private final LabelledMetric<Histogram> nodeBytes;
  private final Histogram writeSeconds;
  private final Histogram reconstructSeconds;
  private final Histogram reconstructChainLength;
  private final LabelledMetric<Counter> baseCache;
  private final LabelledMetric<Counter> nodes;
  private final LabelledMetric<Counter> snapshots;
  private final LabelledMetric<Counter> supernodes;
  private final Counter supernodeInsertedBytes;
  private final Counter verificationFailures;
  private final LabelledMetric<Counter> sectionBytes;
  private final LabelledMetric<Counter> sectionEntries;
  private final SettableLabelledGauge windowStartEpoch;
  private final Counter reconstructListBuilds;
  private final Map<WritePhase, DoubleAdder> writePhaseSeconds = new EnumMap<>(WritePhase.class);

  StateDiffMetrics(final MetricsSystem metricsSystem) {
    this.nodeBytes =
        metricsSystem.createLabelledHistogram(
            TekuMetricCategory.STORAGE,
            "state_diff_node_bytes",
            "Encoded size of written state diff DAG nodes by level",
            BYTES_BUCKETS,
            "level");
    this.writeSeconds =
        metricsSystem.createHistogram(
            TekuMetricCategory.STORAGE,
            "state_diff_write_seconds",
            "Time to compute and encode a state diff DAG node",
            SECONDS_BUCKETS);
    this.reconstructSeconds =
        metricsSystem.createHistogram(
            TekuMetricCategory.STORAGE,
            "state_diff_reconstruct_seconds",
            "Time to reconstruct a state from the state diff DAG",
            SECONDS_BUCKETS);
    this.reconstructChainLength =
        metricsSystem.createHistogram(
            TekuMetricCategory.STORAGE,
            "state_diff_reconstruct_chain_length",
            "Number of nodes applied to reconstruct a state: down to the snapshot, included, or to"
                + " the nearest node whose state is cached, excluded",
            CHAIN_LENGTH_BUCKETS);
    this.baseCache =
        metricsSystem.createLabelledCounter(
            TekuMetricCategory.STORAGE,
            "state_diff_base_cache_total",
            "Diff bases served by the per-level cache (hit) or reconstructed (miss)",
            "result");
    this.nodes =
        metricsSystem.createLabelledCounter(
            TekuMetricCategory.STORAGE,
            "state_diff_nodes_total",
            "State diff DAG nodes written, deleted, or created or repaired on finalization",
            "state");
    this.snapshots =
        metricsSystem.createLabelledCounter(
            TekuMetricCategory.STORAGE,
            "state_diff_snapshots_total",
            "State diff DAG snapshot nodes written, by why no diff was written",
            "reason");
    this.supernodes =
        metricsSystem.createLabelledCounter(
            TekuMetricCategory.STORAGE,
            "state_supernodes_total",
            "Supernode entries inserted, incremented or deleted",
            "op");
    this.supernodeInsertedBytes =
        metricsSystem.createCounter(
            TekuMetricCategory.STORAGE,
            "state_supernode_inserted_bytes_total",
            "Bytes of supernode SSZ inserted");
    this.verificationFailures =
        metricsSystem.createCounter(
            TekuMetricCategory.STORAGE,
            "state_diff_verification_failures_total",
            "State reconstructions that failed: state root mismatch, broken chain, corrupt record or"
                + " missing supernode");
    this.sectionBytes =
        metricsSystem.createLabelledCounter(
            TekuMetricCategory.STORAGE,
            "state_diff_section_bytes_total",
            "Payload bytes of written state diff DAG nodes by section: leaf data, 32 per supernode"
                + " reference and zeroed subtree, written elements of supernode patches, 16 per"
                + " copied subtree, encoded delta bytes before (deltas) and after"
                + " (deltas_compressed) compression",
            "section");
    this.sectionEntries =
        metricsSystem.createLabelledCounter(
            TekuMetricCategory.STORAGE,
            "state_diff_section_entries_total",
            "Payload entries of written state diff DAG nodes by section",
            "section");
    // Registered on the first window start, so a database without a window exposes no value
    this.windowStartEpoch =
        SettableLabelledGauge.create(
            metricsSystem,
            TekuMetricCategory.STORAGE,
            "state_diff_window_start_epoch",
            "First epoch whose finalized state an archive database serves from the state diff DAG");
    this.reconstructListBuilds =
        metricsSystem.createCounter(
            TekuMetricCategory.STORAGE,
            "state_diff_reconstruct_list_builds_total",
            "Packed uint64 lists built by reconstructions: one per delta field and chain, as the"
                + " deltas of a chain are folded before the list is built");
    final LabelledSuppliedMetric phaseSeconds =
        metricsSystem.createLabelledSuppliedCounter(
            TekuMetricCategory.STORAGE,
            "state_diff_write_phase_seconds_total",
            "Time spent writing state diff DAG nodes by phase: base (parent and diff base"
                + " resolution), walk (tree diff), encode (payload serialization and delta"
                + " compression), store (record encoding and puts)",
            "phase");
    for (final WritePhase phase : WritePhase.values()) {
      final DoubleAdder seconds = new DoubleAdder();
      writePhaseSeconds.put(phase, seconds);
      phaseSeconds.labels(seconds::sum, phase.label);
    }
  }

  void onNodeWritten(final int level, final boolean snapshot, final int encodedBytes) {
    nodes.labels("written").inc();
    nodeBytes
        .labels(snapshot ? SNAPSHOT_LEVEL_LABEL : Integer.toString(level))
        .observe(encodedBytes);
  }

  void onSnapshotWritten(final SnapshotReason reason) {
    snapshots.labels(reason.label).inc();
  }

  void onNodeDeleted() {
    nodes.labels("deleted").inc();
  }

  /** For a node deleted on retention or repair whose record or payload could not be decoded. */
  void onNodeDeletedCorrupt() {
    nodes.labels("deleted_corrupt").inc();
  }

  /** For a finalized anchor rewritten as a snapshot because its chain could not be loaded. */
  void onAnchorRepaired() {
    nodes.labels("anchor_repaired").inc();
  }

  void onSections(final DiffSectionSizes sizes) {
    for (final DiffSectionSizes.Section section : sizes.sections()) {
      sectionBytes.labels(section.label()).inc(section.bytes());
      sectionEntries.labels(section.label()).inc(section.entries());
    }
    sectionBytes.labels(DELTAS_COMPRESSED_SECTION).inc(sizes.compressedDeltaBytes());
  }

  void onWindowStart(final UInt64 epoch) {
    windowStartEpoch.set(epoch.doubleValue());
  }

  /** For a finalized anchor whose node had to be created on finalization. */
  void onAnchorCreatedLate() {
    nodes.labels("anchor_created_late").inc();
  }

  void onWrite(final long nanos) {
    writeSeconds.observe(nanos / 1e9);
  }

  void onWritePhases(final WritePhaseNanos nanos) {
    writePhaseSeconds.get(WritePhase.BASE).add(nanos.base() / 1e9);
    writePhaseSeconds.get(WritePhase.WALK).add(nanos.walk() / 1e9);
    writePhaseSeconds.get(WritePhase.ENCODE).add(nanos.encode() / 1e9);
    writePhaseSeconds.get(WritePhase.STORE).add(nanos.store() / 1e9);
  }

  /** The total time recorded for {@code phase}, in seconds. */
  double writePhaseSeconds(final WritePhase phase) {
    return writePhaseSeconds.get(phase).sum();
  }

  void onReconstructListBuilds(final int listBuilds) {
    reconstructListBuilds.inc(listBuilds);
  }

  void onReconstruct(final long nanos, final int chainLength) {
    reconstructSeconds.observe(nanos / 1e9);
    reconstructChainLength.observe(chainLength);
  }

  void onBaseCacheHit() {
    baseCache.labels("hit").inc();
  }

  void onBaseCacheMiss() {
    baseCache.labels("miss").inc();
  }

  void onSupernodeInserted(final int bytes) {
    supernodes.labels("inserted").inc();
    supernodeInsertedBytes.inc(bytes);
  }

  void onSupernodeIncremented() {
    supernodes.labels("incremented").inc();
  }

  void onSupernodeDeleted() {
    supernodes.labels("deleted").inc();
  }

  void onVerificationFailure() {
    verificationFailures.inc();
  }

  enum WritePhase {
    /** Resolving the previous node and parent and obtaining the diff base. */
    BASE("base"),
    /** Diffing the state tree against its base. */
    WALK("walk"),
    /** Serializing the diff payload, compressing its delta entries. */
    ENCODE("encode"),
    /** Encoding the node record and putting it and its supernode references. */
    STORE("store");

    private final String label;

    WritePhase(final String label) {
      this.label = label;
    }
  }

  /** Elapsed nanoseconds of each {@link WritePhase} of one node write. */
  record WritePhaseNanos(long base, long walk, long encode, long store) {

    long total() {
      return base + walk + encode + store;
    }
  }

  enum SnapshotReason {
    /** Requested by the caller, e.g. for an anchor. */
    FORCED("forced"),
    /** The node is at the coarsest level, which bounds every reconstruction chain. */
    PERIODIC("periodic"),
    /** The parent's state is from another milestone. */
    MILESTONE("milestone"),
    /** No parent could be resolved. */
    NO_PARENT("no_parent");

    private final String label;

    SnapshotReason(final String label) {
      this.label = label;
    }

    String label() {
      return label;
    }
  }
}
