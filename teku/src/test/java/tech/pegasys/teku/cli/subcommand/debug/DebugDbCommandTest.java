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

package tech.pegasys.teku.cli.subcommand.debug;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tech.pegasys.teku.storage.server.kvstore.ColumnSize;

class DebugDbCommandTest {

  @Test
  void registersGetColumnSizesNextToGetColumnCounts() {
    final CommandLine commandLine = new CommandLine(new DebugDbCommand());

    assertThat(commandLine.getSubcommands()).containsKeys("get-column-counts", "get-column-sizes");
    assertThat(commandLine.getSubcommands().get("get-column-sizes").getCommandSpec().options())
        .anySatisfy(option -> assertThat(option.longestName()).isEqualTo("--filter"))
        .anySatisfy(option -> assertThat(option.longestName()).isEqualTo("--data-beacon-path"));
  }

  @Test
  void formatBytesShowsBinaryUnitAndRawCount() {
    assertThat(DebugDbCommand.formatBytes(0)).isEqualTo("0 B (0 bytes)");
    assertThat(DebugDbCommand.formatBytes(1023)).isEqualTo("1023 B (1023 bytes)");
    assertThat(DebugDbCommand.formatBytes(1024)).isEqualTo("1.00 KiB (1024 bytes)");
    assertThat(DebugDbCommand.formatBytes(3L * 1024 * 1024 / 2))
        .isEqualTo("1.50 MiB (1572864 bytes)");
    assertThat(DebugDbCommand.formatBytes(5L * 1024 * 1024 * 1024))
        .isEqualTo("5.00 GiB (5368709120 bytes)");
  }

  @Test
  void formatColumnSizesSortsColumnsAndAddsTotal() {
    final List<String> lines =
        DebugDbCommand.formatColumnSizes(
            Map.of(
                "STATE_SUPERNODES", new ColumnSize(2048, 4096, 0),
                "STATE_DIFF_NODES", new ColumnSize(1024, 1024, 0)));

    assertThat(lines).hasSize(3);
    assertThat(lines.get(0))
        .startsWith("STATE_DIFF_NODES: live 1.00 KiB (1024 bytes)")
        .contains("sst 1.00 KiB (1024 bytes)")
        .doesNotContain("blob");
    assertThat(lines.get(1)).startsWith("STATE_SUPERNODES: live 2.00 KiB (2048 bytes)");
    assertThat(lines.get(2))
        .startsWith("           TOTAL: live 3.00 KiB (3072 bytes)")
        .contains("sst 5.00 KiB (5120 bytes)");
  }

  @Test
  void formatColumnSizesShowsBlobFilesWhenPresent() {
    final List<String> lines =
        DebugDbCommand.formatColumnSizes(
            Map.of(
                "BLOCKS", new ColumnSize(10, 20, 2048),
                "STATE_DIFF_NODES", new ColumnSize(10, 20, 0)));

    assertThat(lines.get(0)).endsWith("blob 2.00 KiB (2048 bytes)");
    assertThat(lines.get(1)).endsWith("blob 0 B (0 bytes)");
    assertThat(lines.get(2)).endsWith("blob 2.00 KiB (2048 bytes)");
  }
}
