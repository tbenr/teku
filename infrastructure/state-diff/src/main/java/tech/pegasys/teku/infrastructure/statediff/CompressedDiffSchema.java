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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.tuweni.bytes.Bytes;

/** Decorator that adds gzip compression to an underlying diff schema. */
public class CompressedDiffSchema implements StateDiffSchema {

  private final StateDiffSchema delegate;

  public CompressedDiffSchema(final StateDiffSchema delegate) {
    this.delegate = delegate;
  }

  @Override
  public StateDiff computeDiff(final Bytes baseSsz, final Bytes targetSsz) {
    final StateDiff inner = delegate.computeDiff(baseSsz, targetSsz);
    return new CompressedDiff(inner.serialize(), delegate);
  }

  @Override
  public StateDiff deserialize(final Bytes compressed) {
    final Bytes decompressed = decompress(compressed);
    return new CompressedDiff(decompressed, delegate);
  }

  private static class CompressedDiff implements StateDiff {

    private final Bytes innerSerialized;
    private final StateDiffSchema delegateSchema;

    CompressedDiff(final Bytes innerSerialized, final StateDiffSchema delegateSchema) {
      this.innerSerialized = innerSerialized;
      this.delegateSchema = delegateSchema;
    }

    @Override
    public Bytes apply(final Bytes baseSsz) {
      return delegateSchema.deserialize(innerSerialized).apply(baseSsz);
    }

    @Override
    public Bytes serialize() {
      return compress(innerSerialized);
    }
  }

  static Bytes compress(final Bytes input) {
    try {
      final ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try (final GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
        gzos.write(input.toArrayUnsafe());
      }
      return Bytes.wrap(baos.toByteArray());
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to compress diff", e);
    }
  }

  static Bytes decompress(final Bytes compressed) {
    try {
      final ByteArrayInputStream bais = new ByteArrayInputStream(compressed.toArrayUnsafe());
      try (final GZIPInputStream gzis = new GZIPInputStream(bais)) {
        return Bytes.wrap(gzis.readAllBytes());
      }
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to decompress diff", e);
    }
  }
}
