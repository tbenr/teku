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

package tech.pegasys.teku.infrastructure.statediff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

class DiffLevelsTest {

  private final DiffLevels levels = DiffLevels.PRUNE_DEFAULT;

  @Test
  void rejectsNonDividingPeriods() {
    assertThatThrownBy(() -> DiffLevels.of(List.of(256L, 100L, 1L)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsEmptyList() {
    assertThatThrownBy(() -> DiffLevels.of(List.of())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonDescending() {
    assertThatThrownBy(() -> DiffLevels.of(List.of(16L, 64L, 1L)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsLastNotOne() {
    assertThatThrownBy(() -> DiffLevels.of(List.of(256L, 64L, 16L)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void levelOfAlignedEpochs() {
    assertThat(levels.levelCount()).isEqualTo(4);
    assertThat(levels.levelOf(UInt64.valueOf(0))).isEqualTo(0);
    assertThat(levels.levelOf(UInt64.valueOf(256))).isEqualTo(0);
    assertThat(levels.levelOf(UInt64.valueOf(64))).isEqualTo(1);
    assertThat(levels.levelOf(UInt64.valueOf(320))).isEqualTo(1);
    assertThat(levels.levelOf(UInt64.valueOf(16))).isEqualTo(2);
    assertThat(levels.levelOf(UInt64.valueOf(17))).isEqualTo(3);
    assertThat(levels.isCoarsest(0)).isTrue();
    assertThat(levels.isCoarsest(1)).isFalse();
  }

  @Test
  void maxLevelInRangeSpansBoundary() {
    assertThat(levels.maxLevelInRange(UInt64.valueOf(15), UInt64.valueOf(17))).isEqualTo(2);
    assertThat(levels.maxLevelInRange(UInt64.valueOf(1), UInt64.valueOf(3))).isEqualTo(3);
    assertThat(levels.maxLevelInRange(UInt64.valueOf(250), UInt64.valueOf(260))).isEqualTo(0);
  }
}
