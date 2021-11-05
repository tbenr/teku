/*
 * Copyright 2020 ConsenSys AG.
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

package tech.pegasys.teku.cli.options;

import static tech.pegasys.teku.networking.eth2.P2PConfig.Builder.DEFAULT_PEER_RATE_LIMIT;
import static tech.pegasys.teku.networking.eth2.P2PConfig.Builder.DEFAULT_PEER_REQUEST_LIMIT;

import java.math.BigInteger;
import org.apache.commons.lang3.StringUtils;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import picocli.CommandLine;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.TypeConversionException;
import tech.pegasys.teku.config.TekuConfiguration;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.networks.Eth2NetworkConfiguration;

public class Eth2NetworkOptions {

  @Option(
      names = {"-n", "--network"},
      paramLabel = "<NETWORK>",
      description = "Represents which network to use.",
      arity = "1")
  private String network = "mainnet";

  @CommandLine.Option(
      names = {"--initial-state"},
      paramLabel = "<STRING>",
      description =
          "The initial state. This value should be a file or URL pointing to an SSZ-encoded finalized checkpoint state.",
      arity = "1")
  private String initialState;

  @Option(
      names = {"--eth1-deposit-contract-address"},
      paramLabel = "<ADDRESS>",
      description =
          "Contract address for the deposit contract. Only required when creating a custom network.",
      arity = "1")
  private String eth1DepositContractAddress = null; // Depends on network configuration

  @Option(
      names = {"--Xnetwork-altair-fork-epoch"},
      hidden = true,
      paramLabel = "<epoch>",
      description = "Override the Altair fork activation epoch.",
      arity = "1")
  private UInt64 altairForkEpoch;

  @Option(
      names = {"--Xnetwork-merge-fork-epoch"},
      hidden = true,
      paramLabel = "<epoch>",
      description = "Override the Merge fork activation epoch.",
      arity = "1")
  private UInt64 mergeForkEpoch;

  @Option(
      names = {"--Xnetwork-merge-total-terminal-difficulty-override"},
      hidden = true,
      paramLabel = "<uint256>",
      description = "Override total terminal difficulty for The Merge",
      arity = "1",
      converter = UInt256Converter.class)
  private UInt256 mergeTotalTerminalDifficultyOverride;

  @Option(
      names = {"--Xnetwork-merge-terminal-block-hash-override"},
      hidden = true,
      paramLabel = "<Bytes32 hex>",
      description =
          "Override terminal block hash for The Merge. To be used in conjunction with --Xnetwork-merge-terminal-block-hash-epoch-override",
      arity = "1",
      converter = Bytes32Converter.class)
  private Bytes32 mergeTerminalBlockHashOverride;

  @Option(
      names = {"--Xnetwork-merge-terminal-block-hash-epoch-override"},
      hidden = true,
      paramLabel = "<epoch>",
      description =
          "Override terminal block hash for The Merge. To be used in conjunction with --Xnetwork-merge-terminal-block-hash-override",
      arity = "1")
  private UInt64 mergeTerminalBlockHashEpochOverride;

  @Option(
      names = {"--Xstartup-target-peer-count"},
      paramLabel = "<NUMBER>",
      description = "Number of peers to wait for before considering the node in sync.",
      hidden = true)
  private Integer startupTargetPeerCount;

  @Option(
      names = {"--Xstartup-timeout-seconds"},
      paramLabel = "<NUMBER>",
      description =
          "Timeout in seconds to allow the node to be in sync even if startup target peer count has not yet been reached.",
      hidden = true)
  private Integer startupTimeoutSeconds;

  @Option(
      names = {"--Xpeer-rate-limit"},
      paramLabel = "<NUMBER>",
      description =
          "The number of requested objects per peer to allow per minute before disconnecting the peer.",
      arity = "1",
      hidden = true)
  private Integer peerRateLimit = DEFAULT_PEER_RATE_LIMIT;

  @Option(
      names = {"--Xpeer-request-limit"},
      paramLabel = "<NUMBER>",
      description =
          "The number of requests per peer to allow per minute before disconnecting the peer.",
      arity = "1",
      hidden = true)
  private Integer peerRequestLimit = DEFAULT_PEER_REQUEST_LIMIT;

  @Option(
      names = {"--Xfork-choice-balance-attack-mitigation-enabled"},
      paramLabel = "<BOOLEAN>",
      description = "Whether to enable the HF1 fork choice balance attack mitigation.",
      arity = "0..1",
      fallbackValue = "false",
      hidden = true)
  private Boolean forkChoiceBalanceAttackMitigationEnabled = null;

  public Eth2NetworkConfiguration getNetworkConfiguration() {
    return createEth2NetworkConfig();
  }

  public void configure(final TekuConfiguration.Builder builder) {
    // Create a config instance so we can inspect the values in the other builders
    final Eth2NetworkConfiguration eth2Config = createEth2NetworkConfig();

    builder
        .eth2NetworkConfig(this::configureEth2Network)
        .powchain(
            b -> {
              b.depositContract(eth2Config.getEth1DepositContractAddress());
              b.depositContractDeployBlock(eth2Config.getEth1DepositContractDeployBlock());
            })
        .storageConfiguration(
            b -> b.eth1DepositContract(eth2Config.getEth1DepositContractAddress()))
        .p2p(b -> b.peerRateLimit(peerRateLimit).peerRequestLimit(peerRequestLimit))
        .discovery(b -> b.bootnodes(eth2Config.getDiscoveryBootnodes()))
        .restApi(b -> b.eth1DepositContractAddress(eth2Config.getEth1DepositContractAddress()));
  }

  private Eth2NetworkConfiguration createEth2NetworkConfig() {
    Eth2NetworkConfiguration.Builder builder = Eth2NetworkConfiguration.builder();
    configureEth2Network(builder);
    return builder.build();
  }

  private void configureEth2Network(Eth2NetworkConfiguration.Builder builder) {
    builder.applyNetworkDefaults(network);
    if (startupTargetPeerCount != null) {
      builder.startupTargetPeerCount(startupTargetPeerCount);
    }
    if (startupTimeoutSeconds != null) {
      builder.startupTimeoutSeconds(startupTimeoutSeconds);
    }
    if (eth1DepositContractAddress != null) {
      builder.eth1DepositContractAddress(eth1DepositContractAddress);
    }
    if (StringUtils.isNotBlank(initialState)) {
      builder.customInitialState(initialState);
    }
    if (forkChoiceBalanceAttackMitigationEnabled != null) {
      builder.balanceAttackMitigationEnabled(forkChoiceBalanceAttackMitigationEnabled);
    }
    if (altairForkEpoch != null) {
      builder.altairForkEpoch(altairForkEpoch);
    }
    if (mergeForkEpoch != null) {
      builder.mergeForkEpoch(mergeForkEpoch);
    }
    if (mergeTotalTerminalDifficultyOverride != null) {
      builder.mergeTotalTerminalDifficultyOverride(mergeTotalTerminalDifficultyOverride);
    }
    if (mergeTerminalBlockHashOverride != null) {
      builder.mergeTerminalBlockHashOverride(mergeTerminalBlockHashOverride);
    }
    if (mergeTerminalBlockHashEpochOverride != null) {
      builder.mergeTerminalBlockHashEpochOverride(mergeTerminalBlockHashEpochOverride);
    }
  }

  public String getNetwork() {
    return network;
  }

  private static class UInt256Converter implements ITypeConverter<UInt256> {
    @Override
    public UInt256 convert(final String value) {
      try {
        return UInt256.valueOf(new BigInteger(value));
      } catch (final NumberFormatException e) {
        throw new TypeConversionException(
            "Invalid format: must be a numeric value but was " + value);
      }
    }
  }

  private static class Bytes32Converter implements ITypeConverter<Bytes32> {
    @Override
    public Bytes32 convert(final String value) {
      try {
        return Bytes32.fromHexStringStrict(value);
      } catch (final NumberFormatException e) {
        throw new TypeConversionException(
            "Invalid format: must be a 32 bytes in hex format but was " + value);
      }
    }
  }
}
