/*
 * Copyright Consensys Software Inc., 2026
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

package tech.pegasys.teku.infrastructure.statediff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

class DiffHierarchyTest {

  private DiffHierarchy createTestHierarchy() {
    return createTestHierarchy(Collections.emptySet());
  }

  private DiffHierarchy createTestHierarchy(final Set<UInt64> forkEpochs) {
    // Simplified hierarchy for testing
    final StateDiffSchema stubSchema =
        new StateDiffSchema() {
          @Override
          public StateDiff computeDiff(final Bytes baseSsz, final Bytes targetSsz) {
            return null;
          }

          @Override
          public StateDiff deserialize(final Bytes serialized) {
            return null;
          }
        };

    final List<DiffHierarchy.Level> levels =
        List.of(
            new DiffHierarchy.Level(64, stubSchema), // snapshot every 64 epochs
            new DiffHierarchy.Level(16, stubSchema), // binary diff every 16
            new DiffHierarchy.Level(4, stubSchema), // composite diff every 4
            new DiffHierarchy.Level(1, stubSchema)); // every epoch

    return new DiffHierarchy(levels, forkEpochs);
  }

  @Test
  void getLevelsToWrite_level0AtAlignedEpoch() {
    final DiffHierarchy hierarchy = createTestHierarchy();
    assertThat(hierarchy.getLevelsToWrite(UInt64.valueOf(0))).containsExactly(0);
    assertThat(hierarchy.getLevelsToWrite(UInt64.valueOf(64))).containsExactly(0);
    assertThat(hierarchy.getLevelsToWrite(UInt64.valueOf(128))).containsExactly(0);
  }

  @Test
  void getLevelsToWrite_level1AtAlignedEpoch() {
    final DiffHierarchy hierarchy = createTestHierarchy();
    assertThat(hierarchy.getLevelsToWrite(UInt64.valueOf(16))).containsExactly(1);
    assertThat(hierarchy.getLevelsToWrite(UInt64.valueOf(32))).containsExactly(1);
    assertThat(hierarchy.getLevelsToWrite(UInt64.valueOf(48))).containsExactly(1);
  }

  @Test
  void getLevelsToWrite_level2AtAlignedEpoch() {
    final DiffHierarchy hierarchy = createTestHierarchy();
    assertThat(hierarchy.getLevelsToWrite(UInt64.valueOf(4))).containsExactly(2);
    assertThat(hierarchy.getLevelsToWrite(UInt64.valueOf(8))).containsExactly(2);
    assertThat(hierarchy.getLevelsToWrite(UInt64.valueOf(12))).containsExactly(2);
  }

  @Test
  void getLevelsToWrite_level3ForEveryEpoch() {
    final DiffHierarchy hierarchy = createTestHierarchy();
    assertThat(hierarchy.getLevelsToWrite(UInt64.valueOf(1))).containsExactly(3);
    assertThat(hierarchy.getLevelsToWrite(UInt64.valueOf(5))).containsExactly(3);
    assertThat(hierarchy.getLevelsToWrite(UInt64.valueOf(7))).containsExactly(3);
  }

  @Test
  void getLevelsToWrite_forkEpochForcesSnapshot() {
    final DiffHierarchy hierarchy = createTestHierarchy(Set.of(UInt64.valueOf(10)));
    // Epoch 10 normally gets level 3, but fork forces level 0
    assertThat(hierarchy.getLevelsToWrite(UInt64.valueOf(10))).containsExactly(0);
  }

  @Test
  void getParentEpoch_correctForEachLevel() {
    final DiffHierarchy hierarchy = createTestHierarchy();
    // Level 1 parent: 16 epochs earlier
    assertThat(hierarchy.getParentEpoch(UInt64.valueOf(80), 1)).isEqualTo(UInt64.valueOf(64));
    // Level 2 parent: 4 epochs earlier
    assertThat(hierarchy.getParentEpoch(UInt64.valueOf(68), 2)).isEqualTo(UInt64.valueOf(64));
    // Level 3 parent: 1 epoch earlier
    assertThat(hierarchy.getParentEpoch(UInt64.valueOf(65), 3)).isEqualTo(UInt64.valueOf(64));
  }

  @Test
  void getReconstructionChain_exactSnapshotEpoch() {
    final DiffHierarchy hierarchy = createTestHierarchy();
    final List<DiffHierarchy.LevelAndEpoch> chain =
        hierarchy.getReconstructionChain(UInt64.valueOf(64));

    assertThat(chain).hasSize(1);
    assertThat(chain.get(0).level()).isEqualTo(0);
    assertThat(chain.get(0).epoch()).isEqualTo(UInt64.valueOf(64));
  }

  @Test
  void getReconstructionChain_oneEpochAfterSnapshot() {
    final DiffHierarchy hierarchy = createTestHierarchy();
    final List<DiffHierarchy.LevelAndEpoch> chain =
        hierarchy.getReconstructionChain(UInt64.valueOf(65));

    // Chain: snapshot@64, level3@65
    assertThat(chain).hasSize(2);
    assertThat(chain.get(0)).isEqualTo(new DiffHierarchy.LevelAndEpoch(0, UInt64.valueOf(64)));
    assertThat(chain.get(1)).isEqualTo(new DiffHierarchy.LevelAndEpoch(3, UInt64.valueOf(65)));
  }

  @Test
  void getReconstructionChain_multiLevel() {
    final DiffHierarchy hierarchy = createTestHierarchy();
    // Epoch 85: snapshot@64, level1@80, level2@84, level3@85
    final List<DiffHierarchy.LevelAndEpoch> chain =
        hierarchy.getReconstructionChain(UInt64.valueOf(85));

    assertThat(chain).hasSize(4);
    assertThat(chain.get(0)).isEqualTo(new DiffHierarchy.LevelAndEpoch(0, UInt64.valueOf(64)));
    assertThat(chain.get(1)).isEqualTo(new DiffHierarchy.LevelAndEpoch(1, UInt64.valueOf(80)));
    assertThat(chain.get(2)).isEqualTo(new DiffHierarchy.LevelAndEpoch(2, UInt64.valueOf(84)));
    assertThat(chain.get(3)).isEqualTo(new DiffHierarchy.LevelAndEpoch(3, UInt64.valueOf(85)));
  }

  @Test
  void getReconstructionChain_atLevel1Boundary() {
    final DiffHierarchy hierarchy = createTestHierarchy();
    // Epoch 80: snapshot@64, level1@80
    final List<DiffHierarchy.LevelAndEpoch> chain =
        hierarchy.getReconstructionChain(UInt64.valueOf(80));

    assertThat(chain).hasSize(2);
    assertThat(chain.get(0)).isEqualTo(new DiffHierarchy.LevelAndEpoch(0, UInt64.valueOf(64)));
    assertThat(chain.get(1)).isEqualTo(new DiffHierarchy.LevelAndEpoch(1, UInt64.valueOf(80)));
  }

  @Test
  void getReconstructionChain_forkEpochUsedAsBase() {
    final DiffHierarchy hierarchy = createTestHierarchy(Set.of(UInt64.valueOf(70)));
    // Fork at 70 creates a snapshot that's closer than the regular one at 64
    final List<DiffHierarchy.LevelAndEpoch> chain =
        hierarchy.getReconstructionChain(UInt64.valueOf(73));

    assertThat(chain.get(0)).isEqualTo(new DiffHierarchy.LevelAndEpoch(0, UInt64.valueOf(70)));
    assertThat(chain.get(chain.size() - 1).epoch()).isEqualTo(UInt64.valueOf(73));
  }

  @Test
  void getReconstructionChain_multipleEntriesAtSameLevel() {
    final DiffHierarchy hierarchy = createTestHierarchy();
    // Epoch 87: snapshot@64, level1@80, level2@84, then level3@85,86,87
    final List<DiffHierarchy.LevelAndEpoch> chain =
        hierarchy.getReconstructionChain(UInt64.valueOf(87));

    assertThat(chain)
        .containsExactly(
            new DiffHierarchy.LevelAndEpoch(0, UInt64.valueOf(64)),
            new DiffHierarchy.LevelAndEpoch(1, UInt64.valueOf(80)),
            new DiffHierarchy.LevelAndEpoch(2, UInt64.valueOf(84)),
            new DiffHierarchy.LevelAndEpoch(3, UInt64.valueOf(85)),
            new DiffHierarchy.LevelAndEpoch(3, UInt64.valueOf(86)),
            new DiffHierarchy.LevelAndEpoch(3, UInt64.valueOf(87)));
  }

  @Test
  void getReconstructionChain_multipleLevel1Entries() {
    final DiffHierarchy hierarchy = createTestHierarchy();
    // Epoch 100: snapshot@64, level1@80, level1@96, level2@100
    final List<DiffHierarchy.LevelAndEpoch> chain =
        hierarchy.getReconstructionChain(UInt64.valueOf(100));

    assertThat(chain)
        .containsExactly(
            new DiffHierarchy.LevelAndEpoch(0, UInt64.valueOf(64)),
            new DiffHierarchy.LevelAndEpoch(1, UInt64.valueOf(80)),
            new DiffHierarchy.LevelAndEpoch(1, UInt64.valueOf(96)),
            new DiffHierarchy.LevelAndEpoch(2, UInt64.valueOf(100)));
  }

  @Test
  void getReconstructionChain_epochZero() {
    final DiffHierarchy hierarchy = createTestHierarchy();
    final List<DiffHierarchy.LevelAndEpoch> chain =
        hierarchy.getReconstructionChain(UInt64.valueOf(0));

    assertThat(chain).hasSize(1);
    assertThat(chain.get(0)).isEqualTo(new DiffHierarchy.LevelAndEpoch(0, UInt64.valueOf(0)));
  }
}
