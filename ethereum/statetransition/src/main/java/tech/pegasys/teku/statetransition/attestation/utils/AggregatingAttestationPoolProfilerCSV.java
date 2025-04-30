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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
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
import tech.pegasys.teku.statetransition.attestation.AttestationForkChecker;
import tech.pegasys.teku.statetransition.attestation.utils.RewardBasedAttestationSorter.AttestationWithRewardInfo;
import tech.pegasys.teku.storage.client.RecentChainData;

public class AggregatingAttestationPoolProfilerCSV implements AggregatingAttestationPoolProfiler {
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
    "inblock_reward",
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

  private final FileWriter packingSummaryCsvPrinter;
  private final FileWriter attestationDetailsCsvPrinter;
  private final FileWriter attestationImprovementsCsvPrinter;

  private static final long PROPOSER_REWARD_DENOMINATOR =
      WEIGHT_DENOMINATOR
          .minus(PROPOSER_WEIGHT)
          .times(WEIGHT_DENOMINATOR)
          .dividedBy(PROPOSER_WEIGHT)
          .longValue();

  public AggregatingAttestationPoolProfilerCSV(final Path outputDir) {

    try {
      createDirectory(outputDir);

      File packingSummaryFile = outputDir.resolve("packing_summary.csv").toFile();

      if (packingSummaryFile.exists()) {
        packingSummaryCsvPrinter = new FileWriter(packingSummaryFile, StandardCharsets.UTF_8, true);
      } else {
        packingSummaryCsvPrinter =
            new FileWriter(packingSummaryFile, StandardCharsets.UTF_8, false);
        packingSummaryCsvPrinter.write(String.join(",", PACKING_SUMMARY_HEADERS));
      }

    } catch (final IOException e) {
      throw new RuntimeException(e);
    }

    try {
      File attestationsDetailsFile = outputDir.resolve("attestations_details.csv").toFile();
      if (attestationsDetailsFile.exists()) {
        attestationDetailsCsvPrinter =
            new FileWriter(attestationsDetailsFile, StandardCharsets.UTF_8, true);
      } else {
        attestationDetailsCsvPrinter =
            new FileWriter(attestationsDetailsFile, StandardCharsets.UTF_8, false);
        attestationDetailsCsvPrinter.write(String.join(",", ATTESTATION_DETAILS_HEADERS));
      }
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }

    try {
      File packingSummaryFile = outputDir.resolve("fill_up_details.csv").toFile();

      if (packingSummaryFile.exists()) {
        attestationImprovementsCsvPrinter =
            new FileWriter(packingSummaryFile, StandardCharsets.UTF_8, true);
      } else {
        attestationImprovementsCsvPrinter =
            new FileWriter(packingSummaryFile, StandardCharsets.UTF_8, false);
        attestationDetailsCsvPrinter.write(String.join(",",ATTESTATION_IMPROVEMENT_HEADERS));
      }
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static final Logger LOG = LogManager.getLogger();

  @Override
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

      var attestationRewards =
          calculateAttestationRewards(
              attestationPacking,
              BlockProcessorAltair.required(spec.atSlot(slot).getBlockProcessor()),
              preState);

      var rewards = gweiToEth(UInt64.valueOf(attestationRewards.stream().reduce(0L, Long::sum)));

      LOG.info(
          "getAttestationsForBlock for {} produced {} attestations, rewards: {} ETH, timing: {} milliseconds",
          slot,
          attestationPacking.size(),
          rewards,
          packingTotalTimeMillis);

      try {
        packingSummaryCsvPrinter.write(String.join(",",
            slot.toString(),
            String.valueOf(aggregatingAttestationPoolSize),
                String.valueOf(attestationPacking.size()),
                String.valueOf(packingTotalTimeMillis),
            rewards));
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
                  attestationDetailsCsvPrinter.write(String.join(
                          slot.toString(),
                          String.valueOf(i),
                      preState.getSlot().minus(data.getSlot()).toString(),
                      data.getBeaconBlockRoot().toHexString(),
                      data.getSource().getEpoch().toString(),
                      data.getTarget().getEpoch().toString(),
                          String.valueOf(attestation.getAggregationBits().getBitCount()),
                      attestation
                          .getCommitteeBits()
                          .map(sszBits -> String.valueOf(sszBits.getBitCount()))
                          .orElse("N/A"),
                      getEthRewardFromNumerator(UInt64.valueOf(numerator)),
                      gweiToEth(UInt64.valueOf(attestationRewards.get(i))),
                      data.toString()));
                } catch (IOException e) {
                  LOG.error("Failed to write to CSV", e);
                }
              });

    } catch (final Exception e) {
      LOG.error("Error occurred while profiling AggregatingAttestationPool", e);
    } finally {
      try {
        packingSummaryCsvPrinter.flush();
      } catch (IOException e) {
        LOG.error("Failed to flush CSV printer", e);
      }

      try {
        attestationDetailsCsvPrinter.flush();
      } catch (IOException e) {
        LOG.error("Failed to flush CSV printer", e);
      }

      try {
        attestationImprovementsCsvPrinter.flush();
      } catch (IOException e) {
        LOG.error("Failed to flush CSV printer", e);
      }
    }
  }

  @SuppressWarnings("NonFinalStaticField")
  static UInt64 lastSlot = UInt64.ZERO;

  @SuppressWarnings("NonFinalStaticField")
  static int lastAttestationIndex = -1;

  @Override
  public void onPreFillUp(
      final BeaconState stateAtBlockSlot,
      final AttestationWithRewardInfo validatableAttestationWithSortingReward) {
    if (stateAtBlockSlot.getSlot().equals(lastSlot)) {
      lastAttestationIndex = lastAttestationIndex + 1;
    } else {
      lastSlot = stateAtBlockSlot.getSlot();
      lastAttestationIndex = 0;
    }

    var attestation = validatableAttestationWithSortingReward.getAttestation().getAttestation();
    var sortingRewardNumerator = validatableAttestationWithSortingReward.getRewardNumerator();

    try {
      attestationImprovementsCsvPrinter.write(String.join(
          stateAtBlockSlot.getSlot().toString(),
          String.valueOf(lastAttestationIndex),
              String.valueOf(attestation.getAggregationBits().getBitCount()),
          "0", // not filled up
          getEthRewardFromNumerator(sortingRewardNumerator),
          attestation.getData().getBeaconBlockRoot().toString(),
          attestation.getData().getSource().getEpoch().toString(),
          attestation.getData().getTarget().getEpoch().toString(),
          attestation.getData().toString()));
    } catch (final IOException e) {
      LOG.error("Error printing CSV record", e);
    }
  }

  @Override
  public void onPostFillUp(
      final BeaconState stateAtBlockSlot,
      final AttestationWithRewardInfo validatableAttestationWithSortingReward) {

    var attestation = validatableAttestationWithSortingReward.getAttestation().getAttestation();
    var sortingRewardNumerator = validatableAttestationWithSortingReward.getRewardNumerator();

    try {
      attestationImprovementsCsvPrinter.write(String.join(
          stateAtBlockSlot.getSlot().toString(),
              String.valueOf(lastAttestationIndex),
              String.valueOf(attestation.getAggregationBits().getBitCount()),
          "1", // filled up
          getEthRewardFromNumerator(sortingRewardNumerator),
          attestation.getData().getBeaconBlockRoot().toString(),
          attestation.getData().getSource().getEpoch().toString(),
          attestation.getData().getTarget().getEpoch().toString(),
          attestation.getData().toString()));
    } catch (final IOException e) {
      LOG.error("Error printing CSV record", e);
    }
  }

  private String getEthRewardFromNumerator(final UInt64 numerator) {
    return gweiToEth(numerator.dividedBy(PROPOSER_REWARD_DENOMINATOR));
  }

  private List<Long> calculateAttestationRewards(
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
        .map(maybeValue -> maybeValue.orElse(UInt64.ZERO))
        .map(UInt64::longValue)
        .toList();
  }

  private void createDirectory(final Path path) {
    if (!path.toFile().mkdirs()) {
      if (!path.toFile().exists()) {
        LOG.error("Unable to create directory {}", path);
      }
    }
  }
}
