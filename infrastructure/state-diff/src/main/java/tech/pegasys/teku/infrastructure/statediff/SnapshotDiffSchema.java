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

import org.apache.tuweni.bytes.Bytes;

/**
 * Level 0 schema: stores the full compressed SSZ state. Ignores the base state entirely - always
 * produces a snapshot.
 */
public class SnapshotDiffSchema implements StateDiffSchema {

  @Override
  public StateDiff computeDiff(final Bytes baseSsz, final Bytes targetSsz) {
    return new SnapshotDiff(CompressedDiffSchema.compress(targetSsz));
  }

  @Override
  public StateDiff deserialize(final Bytes serialized) {
    return new SnapshotDiff(serialized);
  }

  private record SnapshotDiff(Bytes compressed) implements StateDiff {

    @Override
    public Bytes apply(final Bytes baseSsz) {
      // Ignore baseSsz - this is a full snapshot
      return CompressedDiffSchema.decompress(compressed);
    }

    @Override
    public Bytes serialize() {
      return compressed;
    }
  }
}
