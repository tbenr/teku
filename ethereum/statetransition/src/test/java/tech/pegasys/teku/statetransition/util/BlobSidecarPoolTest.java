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

package tech.pegasys.teku.statetransition.util;

import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import tech.pegasys.teku.infrastructure.metrics.SettableLabelledGauge;
import tech.pegasys.teku.infrastructure.metrics.StubMetricsSystem;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.statetransition.blobs.BlockBlobsSidecarsTrackerFactory;

public class BlobSidecarPoolTest {
  private final Spec spec = TestSpecFactory.createMinimalDeneb();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final UInt64 historicalTolerance = UInt64.valueOf(5);
  private final UInt64 futureTolerance = UInt64.valueOf(2);
  private final StubMetricsSystem metricsSystem = new StubMetricsSystem();
  private final SettableLabelledGauge gauge = mock(SettableLabelledGauge.class);
  private final int maxItems = 15;
  private final BlockBlobsSidecarsTrackerFactory blockBlobsSidecarsTrackerFactory =
      mock(BlockBlobsSidecarsTrackerFactory.class);
  private final BlobSidecarPoolImpl blobSidecarPool =
      new PoolFactory(metricsSystem, blockBlobsSidecarsTrackerFactory)
          .createPoolForBlobSidecar(spec, historicalTolerance, futureTolerance, maxItems);

  private UInt64 currentSlot = historicalTolerance.times(2);
  private final List<Bytes32> requiredRootEvents = new ArrayList<>();
  private final List<Bytes32> requiredRootDroppedEvents = new ArrayList<>();

  @BeforeEach
  public void setup() {
    // Set up slot
    setSlot(currentSlot);
  }

  private void setSlot(final long slot) {
    setSlot(UInt64.valueOf(slot));
  }

  private void setSlot(final UInt64 slot) {
    currentSlot = slot;
    blobSidecarPool.onSlot(slot);
  }
}
