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

import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffCodec;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffCodec.SectionBytes;

/** Node records encoded from a record alone, payload included, for tests. */
final class TestNodeRecords {

  /** The header length the record codec reserves before a payload. */
  private static final int HEADER_SIZE =
      StateDiffNodeRecordCodec.encodePayload(
              new TreeDiff(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()))
          .prefixLength();

  private TestNodeRecords() {}

  /** Encodes {@code record}, whose payload may be any bytes, a corrupt one included. */
  static Bytes encode(final StateDiffNodeRecord record) {
    final MutableBytes bytes = MutableBytes.create(HEADER_SIZE + record.payload().size());
    record.payload().copyTo(bytes, HEADER_SIZE);
    return StateDiffNodeRecordCodec.encode(
        record, new TreeDiffCodec.Encoded(bytes, HEADER_SIZE, new SectionBytes(0, 0, 0, 0, 0, 0)));
  }
}
