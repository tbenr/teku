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

/**
 * Thrown when the state diff DAG is inconsistent: a reconstructed state does not match its recorded
 * state root, a node's reconstruction chain is broken, or a node record or payload cannot be
 * decoded.
 */
public class StateDiffVerificationException extends RuntimeException {

  public StateDiffVerificationException(final String message) {
    super(message);
  }

  public StateDiffVerificationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
