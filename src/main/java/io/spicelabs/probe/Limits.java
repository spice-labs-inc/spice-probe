package io.spicelabs.probe;

import java.time.Duration;

/**
 * How much work one archive may cost before its dates are given up as unknown.
 *
 * <p>Only a compressed tar needs this: it has no index, so its entry headers are found by
 * decompressing everything in front of them. Every other format is read from a header or a
 * directory of a few kilobytes. An archive that runs out of budget is treated as having no
 * known dates, which means it is analysed.
 *
 * @param maxBytes the most decompressed bytes to read from one archive
 * @param maxTime the longest to spend on one archive
 */
public record Limits(long maxBytes, Duration maxTime) {

  /** 256 MiB of decompressed data or 30 seconds, whichever comes first. */
  public static final Limits DEFAULT = new Limits(256L * 1024 * 1024, Duration.ofSeconds(30));

  /**
   * Limits checked to be positive.
   *
   * @param maxBytes the most decompressed bytes to read from one archive
   * @param maxTime the longest to spend on one archive
   */
  public Limits {
    if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
    if (maxTime == null || maxTime.isNegative() || maxTime.isZero()) {
      throw new IllegalArgumentException("maxTime must be positive: " + maxTime);
    }
  }
}
