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

package tech.pegasys.teku.storage.server.kvstore;

/**
 * On-disk size estimates of one column, as reported by the storage engine, in bytes.
 *
 * @param estimateLiveDataSize the estimated size of the live (not deleted or overwritten) data
 * @param totalSstFilesSize the total size of the column's table files, garbage included
 * @param totalBlobFileSize the total size of the column's blob files; zero when blob files are not
 *     enabled for the column
 */
public record ColumnSize(
    long estimateLiveDataSize, long totalSstFilesSize, long totalBlobFileSize) {

  public static final ColumnSize EMPTY = new ColumnSize(0, 0, 0);

  public ColumnSize plus(final ColumnSize other) {
    return new ColumnSize(
        estimateLiveDataSize + other.estimateLiveDataSize,
        totalSstFilesSize + other.totalSstFilesSize,
        totalBlobFileSize + other.totalBlobFileSize);
  }
}
