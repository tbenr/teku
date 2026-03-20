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

package tech.pegasys.teku.storage.protoarray;

/**
 * Fork-aware comparison policy used during head selection when a parent is deciding between two
 * viable children.
 *
 * <p>A positive value means {@code candidateChild} should replace {@code currentBestChild}. A
 * negative value means the current best child should remain. Zero means the caller should fall back
 * to the default weight/root ordering.
 */
interface HeadSelectionPolicy {

  int compareChildren(
      ProtoNode candidateChild,
      ProtoNode currentBestChild,
      ProtoNode parent,
      ProtoArray protoArray);
}
