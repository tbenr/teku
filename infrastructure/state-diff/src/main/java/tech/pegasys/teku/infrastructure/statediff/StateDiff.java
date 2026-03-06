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

/** A computed diff that can be applied to reconstruct a target state from a base state. */
public interface StateDiff {

  /** Reconstruct target SSZ bytes from base SSZ bytes by applying this diff. */
  Bytes apply(Bytes baseSsz);

  /** Serialize this diff to bytes for storage. */
  Bytes serialize();
}
