/*
 * Copyright Consensys Software Inc., 2024
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

package tech.pegasys.teku.spec.config;

import java.util.Optional;
import tech.pegasys.teku.infrastructure.bytes.Bytes4;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

public interface SpecConfigEip7732 extends SpecConfigElectra {

  static SpecConfigEip7732 required(final SpecConfig specConfig) {
    return specConfig
        .toVersionEip7732()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Expected Eip7732 spec config but got: "
                        + specConfig.getClass().getSimpleName()));
  }

  Bytes4 getEip7732ForkVersion();

  UInt64 getEip7732ForkEpoch();

  int getPtcSize();

  int getMaxPayloadAttestations();

  int getKzgCommitmentInclusionProofDepthEip7732();

  @Override
  Optional<SpecConfigEip7732> toVersionEip7732();
}