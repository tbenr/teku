/*
 * Copyright Consensys Software Inc., 2025
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

package tech.pegasys.teku.statetransition.attestation.utils;

import static tech.pegasys.teku.infrastructure.logging.Converter.gweiToEth;
import static tech.pegasys.teku.spec.constants.IncentivizationWeights.PROPOSER_WEIGHT;
import static tech.pegasys.teku.spec.constants.IncentivizationWeights.WEIGHT_DENOMINATOR;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tech.pegasys.teku.bls.BLSSignatureVerifier;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.cache.IndexedAttestationCache;
import tech.pegasys.teku.spec.datastructures.operations.Attestation;
import tech.pegasys.teku.spec.datastructures.operations.AttestationData;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.altair.BeaconStateAltair;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.altair.MutableBeaconStateAltair;
import tech.pegasys.teku.spec.logic.common.block.AbstractBlockProcessor;
import tech.pegasys.teku.spec.logic.versions.altair.block.BlockProcessorAltair;
import tech.pegasys.teku.statetransition.attestation.AggregatingAttestationPool;
import tech.pegasys.teku.statetransition.attestation.AggregatingAttestationPoolV2.ValidatableAttestationWithSortingReward;
import tech.pegasys.teku.statetransition.attestation.AttestationForkChecker;
import tech.pegasys.teku.storage.client.RecentChainData;

public class AggregatingAttestationPoolProfilerCSV {

  public static final AggregatingAttestationPoolProfilerCSV NOOP =
      new AggregatingAttestationPoolProfilerCSV() {
        @Override
        public void execute(
            final Spec spec,
            final UInt64 slot,
            final RecentChainData recentChainData,
            final AggregatingAttestationPool aggregatingAttestationPool) {
          // No-op
        }

        @Override
        public void onPreFillUp(
            final BeaconState stateAtBlockSlot,
            final ValidatableAttestationWithSortingReward attestation) {
          // No-op
        }

        @Override
        public void onPostFillUp(
            final BeaconState stateAtBlockSlot,
            final ValidatableAttestationWithSortingReward attestation) {
          // No-op
        }
      };
  public static final AggregatingAttestationPoolProfilerCSV INSTANCE =
      new AggregatingAttestationPoolProfilerCSV();

  private static final String[] PACKING_SUMMARY_HEADERS = {
    "slot", "total_pool_size", "packed_attestations", "packing_time_millis", "total_rewards_eth"
  };

  private static final String[] ATTESTATION_DETAILS_HEADERS = {
    "slot",
    "index_in_block",
    "distance",
    "root",
    "source",
    "target",
    "bits_count",
    "committee_bits_count",
    "final_reward",
    "data",
  };

  private static final String[] ATTESTATION_IMPROVEMENT_HEADERS = {
    "slot",
    "index_in_block",
    "attestation_bits_count",
    "filled_up",
    "sorting_reward",
    "block_root",
    "source",
    "target",
    "data"
  };

  private static final FileWriter PACKING_SUMMARY_FILE_WRITER;
  private static final CSVPrinter PACKING_SUMMARY_CSV_PRINTER;

  private static final FileWriter ATTESTATION_DETAILS_FILE_WRITER;
  private static final CSVPrinter ATTESTATION_DETAILS_CSV_PRINTER;

  private static final FileWriter ATTESTATION_IMPROVEMENTS_FILE_WRITER;
  private static final CSVPrinter ATTESTATION_IMPROVEMENTS_CSV_PRINTER;

  private static final long PROPOSER_REWARD_DENOMINATOR =
      WEIGHT_DENOMINATOR
          .minus(PROPOSER_WEIGHT)
          .times(WEIGHT_DENOMINATOR)
          .dividedBy(PROPOSER_WEIGHT)
          .longValue();

  static {
    final String tekuProfilerCsvBasepath = System.getenv("TEKU_PROFILER_CSV_BASEPATH");

    try {
      CSVFormat.Builder csvFormatBuilder = CSVFormat.DEFAULT.builder();
      File packingSummaryFile = new File(tekuProfilerCsvBasepath + "/packing_summary.csv");

      if (packingSummaryFile.exists()) {
        PACKING_SUMMARY_FILE_WRITER =
            new FileWriter(packingSummaryFile, StandardCharsets.UTF_8, true);
        csvFormatBuilder.setSkipHeaderRecord(true);
      } else {
        PACKING_SUMMARY_FILE_WRITER =
            new FileWriter(packingSummaryFile, StandardCharsets.UTF_8, false);
        csvFormatBuilder.setHeader(PACKING_SUMMARY_HEADERS);
      }
      PACKING_SUMMARY_CSV_PRINTER =
          new CSVPrinter(PACKING_SUMMARY_FILE_WRITER, csvFormatBuilder.get());
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }

    try {
      CSVFormat.Builder csvFormatBuilder = CSVFormat.DEFAULT.builder();
      File attestationsDetailsFile = new File(tekuProfilerCsvBasepath + "/attestations_details.csv");
      if (attestationsDetailsFile.exists()) {
        ATTESTATION_DETAILS_FILE_WRITER =
            new FileWriter(attestationsDetailsFile, StandardCharsets.UTF_8, true);
        csvFormatBuilder.setSkipHeaderRecord(true);
      } else {
        ATTESTATION_DETAILS_FILE_WRITER =
            new FileWriter(attestationsDetailsFile, StandardCharsets.UTF_8, false);
        csvFormatBuilder.setHeader(ATTESTATION_DETAILS_HEADERS);
      }
      ATTESTATION_DETAILS_CSV_PRINTER =
          new CSVPrinter(ATTESTATION_DETAILS_FILE_WRITER, csvFormatBuilder.get());
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }

    try {
      CSVFormat.Builder csvFormatBuilder = CSVFormat.DEFAULT.builder();
      File packingSummaryFile = new File(tekuProfilerCsvBasepath + "/fill_up_details.csv");

      if (packingSummaryFile.exists()) {
        ATTESTATION_IMPROVEMENTS_FILE_WRITER =
            new FileWriter(packingSummaryFile, StandardCharsets.UTF_8, true);
        csvFormatBuilder.setSkipHeaderRecord(true);
      } else {
        ATTESTATION_IMPROVEMENTS_FILE_WRITER =
            new FileWriter(packingSummaryFile, StandardCharsets.UTF_8, false);
        csvFormatBuilder.setHeader(ATTESTATION_IMPROVEMENT_HEADERS);
      }
      ATTESTATION_IMPROVEMENTS_CSV_PRINTER =
          new CSVPrinter(ATTESTATION_IMPROVEMENTS_FILE_WRITER, csvFormatBuilder.get());
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  private AggregatingAttestationPoolProfilerCSV() {
    // Private constructor to prevent instantiation
  }

  private static final Logger LOG = LogManager.getLogger();

  public void execute(
      final Spec spec,
      final UInt64 slot,
      final RecentChainData recentChainData,
      final AggregatingAttestationPool aggregatingAttestationPool) {
    final Optional<SafeFuture<BeaconState>> headState = recentChainData.getBestState();
    if (headState.isEmpty()) {
      return;
    }

    try {
      var preState = spec.processSlots(headState.get().join(), slot);

      final int aggregatingAttestationPoolSize = aggregatingAttestationPool.getSize();
      LOG.info("Pool size: {}", aggregatingAttestationPoolSize);

      var getAttestationsForBlockStart = System.nanoTime();
      var attestationPacking =
          aggregatingAttestationPool.getAttestationsForBlock(
              preState, new AttestationForkChecker(spec, preState));
      var getAttestationsForBlockEnd = System.nanoTime();
      var packingTotalTimeMillis =
          (getAttestationsForBlockEnd - getAttestationsForBlockStart) / 1_000_000;

      spec.atSlot(slot)
          .getBlockProcessor()
          .processAttestations(
              BeaconStateAltair.required(preState).createWritableCopy(),
              attestationPacking,
              BLSSignatureVerifier.SIMPLE);

      var rewards =
          gweiToEth(
              UInt64.valueOf(
                  calculateAttestationRewards(
                      attestationPacking,
                      BlockProcessorAltair.required(spec.atSlot(slot).getBlockProcessor()),
                      preState)));

      LOG.info(
          "getAttestationsForBlock for {} produced {} attestations, rewards: {} ETH, timing: {} milliseconds",
          slot,
          attestationPacking.size(),
          rewards,
          packingTotalTimeMillis);

      try {
        PACKING_SUMMARY_CSV_PRINTER.printRecord(
            slot,
            aggregatingAttestationPoolSize,
            attestationPacking.size(),
            packingTotalTimeMillis,
            rewards);
      } catch (IOException e) {
        LOG.warn("Failed to write to CSV", e);
      }

      var rewardsCalculator = AttestationRewardCalculator.create(spec, preState);

      IntStream.range(0, attestationPacking.size())
          .forEach(
              i -> {
                final Attestation attestation = attestationPacking.get(i);
                final AttestationData data = attestation.getData();

                var numerator = rewardsCalculator.getRewardNumeratorForAttestation(attestation);
                try {
                  ATTESTATION_DETAILS_CSV_PRINTER.printRecord(
                      slot,
                      i,
                      preState.getSlot().minus(data.getSlot()),
                      data.getBeaconBlockRoot().toHexString(),
                      data.getSource().getEpoch(),
                      data.getTarget().getEpoch(),
                      attestation.getAggregationBits().getBitCount(),
                      attestation
                          .getCommitteeBits()
                          .map(sszBits -> String.valueOf(sszBits.getBitCount()))
                          .orElse("N/A"),
                      getEthRewardFromNumerator(numerator),
                      data.toString());
                } catch (IOException e) {
                  LOG.error("Failed to write to CSV", e);
                }
              });

    } catch (final Exception e) {
      LOG.error("Error occurred while profiling AggregatingAttestationPool", e);
    } finally {
      try {
        PACKING_SUMMARY_CSV_PRINTER.flush();
      } catch (IOException e) {
        LOG.error("Failed to flush CSV printer", e);
      }

      try {
        ATTESTATION_DETAILS_CSV_PRINTER.flush();
      } catch (IOException e) {
        LOG.error("Failed to flush CSV printer", e);
      }

      try {
        ATTESTATION_IMPROVEMENTS_CSV_PRINTER.flush();
      } catch (IOException e) {
        LOG.error("Failed to flush CSV printer", e);
      }
    }
  }

  @SuppressWarnings("NonFinalStaticField")
  static UInt64 lastSlot = UInt64.ZERO;

  @SuppressWarnings("NonFinalStaticField")
  static int lastAttestationIndex = -1;

  public void onPreFillUp(
      final BeaconState stateAtBlockSlot,
      final ValidatableAttestationWithSortingReward validatableAttestationWithSortingReward) {
    if (stateAtBlockSlot.getSlot().equals(lastSlot)) {
      lastAttestationIndex = lastAttestationIndex + 1;
    } else {
      lastSlot = stateAtBlockSlot.getSlot();
      lastAttestationIndex = 0;
    }

    var attestation =
        validatableAttestationWithSortingReward.validatableAttestation().getAttestation();
    var sortingRewardNumerator = validatableAttestationWithSortingReward.sortingRewardNumerator();

    try {
      ATTESTATION_IMPROVEMENTS_CSV_PRINTER.printRecord(
          stateAtBlockSlot.getSlot(),
          lastAttestationIndex,
          attestation.getAggregationBits().getBitCount(),
          0, // not filled up
          getEthRewardFromNumerator(sortingRewardNumerator),
          attestation.getData().getBeaconBlockRoot(),
          attestation.getData().getSource().getEpoch(),
          attestation.getData().getTarget().getEpoch(),
          attestation.getData());
    } catch (final IOException e) {
      LOG.error("Error printing CSV record", e);
    }
  }

  public void onPostFillUp(
      final BeaconState stateAtBlockSlot,
      final ValidatableAttestationWithSortingReward validatableAttestationWithSortingReward) {

    var attestation =
        validatableAttestationWithSortingReward.validatableAttestation().getAttestation();
    var sortingRewardNumerator = validatableAttestationWithSortingReward.sortingRewardNumerator();

    try {
      ATTESTATION_IMPROVEMENTS_CSV_PRINTER.printRecord(
          stateAtBlockSlot.getSlot(),
          lastAttestationIndex,
          attestation.getAggregationBits().getBitCount(),
          1, // filled up
          getEthRewardFromNumerator(sortingRewardNumerator),
          attestation.getData().getBeaconBlockRoot(),
          attestation.getData().getSource().getEpoch(),
          attestation.getData().getTarget().getEpoch(),
          attestation.getData());
    } catch (final IOException e) {
      LOG.error("Error printing CSV record", e);
    }
  }

  private String getEthRewardFromNumerator(final long numerator) {
    return gweiToEth(UInt64.valueOf(Long.divideUnsigned(numerator, PROPOSER_REWARD_DENOMINATOR)));
  }

  private long calculateAttestationRewards(
      final SszList<Attestation> attestations,
      final BlockProcessorAltair blockProcessor,
      final BeaconState preState) {
    final List<Optional<UInt64>> rewards = new ArrayList<>();
    final MutableBeaconStateAltair mutableBeaconStateAltair =
        BeaconStateAltair.required(preState).createWritableCopy();
    final AbstractBlockProcessor.IndexedAttestationProvider indexedAttestationProvider =
        blockProcessor.createIndexedAttestationProvider(
            mutableBeaconStateAltair, IndexedAttestationCache.capturing());
    attestations.forEach(
        attestation ->
            rewards.add(
                blockProcessor.processAttestationProposerReward(
                    mutableBeaconStateAltair, attestation, indexedAttestationProvider)));

    return rewards.stream()
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(UInt64::longValue)
        .reduce(0L, Long::sum);
  }
}
