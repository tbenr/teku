/*
 * Copyright 2021 ConsenSys AG.
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

package tech.pegasys.teku.spec.executionengine;

import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;

public class ForkchoicheState {
  private final Bytes32 headBlockHash;
  private final Bytes32 safeBlockHash;
  private final Bytes32 finalizedBlockHash;

  public static Optional<ForkchoicheState> fromHeadAndFinalized(
      Optional<Bytes32> maybeHeadBlock, Bytes32 finalizedBlock) {
    return maybeHeadBlock.map(
        headBlock -> new ForkchoicheState(headBlock, headBlock, finalizedBlock));
  }

  public ForkchoicheState(
      Bytes32 headBlockHash, Bytes32 safeBlockHash, Bytes32 finalizedBlockHash) {
    this.headBlockHash = headBlockHash;
    this.safeBlockHash = safeBlockHash;
    this.finalizedBlockHash = finalizedBlockHash;
  }

  public Bytes32 getHeadBlockHash() {
    return headBlockHash;
  }

  public Bytes32 getSafeBlockHash() {
    return safeBlockHash;
  }

  public Bytes32 getFinalizedBlockHash() {
    return finalizedBlockHash;
  }
}
