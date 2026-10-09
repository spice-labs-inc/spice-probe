package io.spicelabs.probe;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A stream that stops with {@link Exhausted} once its {@link Limits} are used up. Only bytes
 * actually read count: a skip over an uncompressed file is a seek, and costs nothing.
 */
final class Metered extends FilterInputStream {

  /** The budget ran out before the archive's dates were all read. */
  static final class Exhausted extends IOException {
    Exhausted(String message) {
      super(message);
    }
  }

  private final long maxBytes;
  private final long deadline;
  private long read = 0;

  Metered(InputStream in, Limits limits) {
    super(in);
    this.maxBytes = limits.maxBytes();
    this.deadline = System.nanoTime() + limits.maxTime().toNanos();
  }

  @Override
  public int read() throws IOException {
    int b = super.read();
    if (b >= 0) spend(1);
    return b;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    int n = super.read(buffer, offset, length);
    if (n > 0) spend(n);
    return n;
  }

  private void spend(long n) throws Exhausted {
    read += n;
    if (read > maxBytes) throw new Exhausted("read more than " + maxBytes + " bytes");
    if (System.nanoTime() - deadline > 0) throw new Exhausted("took longer than allowed");
  }
}
