package io.spicelabs.probe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * The formats whose dates sit in fixed-layout headers, read here by hand rather than through
 * a library that would read more than it needs.
 */
final class Headers {

  private Headers() {}

  /** More members than any real ar archive has; past this, the layout is not what it seems. */
  private static final int MAX_AR_MEMBERS = 10_000;

  /**
   * The mtime in each member header of an ar archive, which is what a .deb is (debian-binary,
   * control.tar.*, data.tar.*). The members are skipped, not read.
   */
  static Optional<Instant> ar(Path file, Instant cutoff) throws IOException {
    long size = Files.size(file);
    long position = 8; // "!<arch>\n"
    for (int member = 0; member < MAX_AR_MEMBERS && position + 60 <= size; member++) {
      byte[] header = ArchiveDates.read(file, position, 60);
      if (header.length < 60 || header[58] != '`' || header[59] != '\n') break;
      long mtime = decimal(header, 16, 12);
      Optional<Instant> late = ArchiveDates.after(Instant.ofEpochSecond(mtime), cutoff);
      if (late.isPresent()) return late;
      long length = decimal(header, 48, 10);
      position += 60 + length + (length & 1);
    }
    return Optional.empty();
  }

  /** RPM's header tag for the time the package was built, in epoch seconds. */
  private static final int RPMTAG_BUILDTIME = 1006;

  private static final int RPM_INT32 = 4;

  /**
   * An RPM's build time, from its header: the lead (96 bytes), then the signature header,
   * padded to 8 bytes, then the header proper, which holds BUILDTIME. The payload is not read.
   */
  static Optional<Instant> rpm(Path file, Instant cutoff) throws IOException {
    long signature = 96;
    long main = signature + headerLength(file, signature);
    main += (8 - main % 8) % 8;
    ByteBuffer intro = ByteBuffer.wrap(ArchiveDates.read(file, main, 16));
    if (intro.remaining() < 16 || !headerMagic(intro)) return Optional.empty();
    int entries = intro.getInt(8);
    if (entries < 0 || entries > 100_000) return Optional.empty();
    ByteBuffer index = ByteBuffer.wrap(ArchiveDates.read(file, main + 16, entries * 16));
    long store = main + 16 + entries * 16L;
    for (int i = 0; i + 16 <= index.limit(); i += 16) {
      if (index.getInt(i) == RPMTAG_BUILDTIME && index.getInt(i + 4) == RPM_INT32) {
        ByteBuffer value = ByteBuffer.wrap(ArchiveDates.read(file, store + index.getInt(i + 8), 4));
        if (value.remaining() < 4) return Optional.empty();
        long seconds = Integer.toUnsignedLong(value.getInt(0));
        return ArchiveDates.after(Instant.ofEpochSecond(seconds), cutoff);
      }
    }
    return Optional.empty();
  }

  /** The length of the RPM header structure at {@code position}: intro, index and store. */
  private static long headerLength(Path file, long position) throws IOException {
    ByteBuffer intro = ByteBuffer.wrap(ArchiveDates.read(file, position, 16));
    if (intro.remaining() < 16 || !headerMagic(intro)) throw new IOException("not an RPM header");
    long entries = Integer.toUnsignedLong(intro.getInt(8));
    long store = Integer.toUnsignedLong(intro.getInt(12));
    return 16 + entries * 16 + store;
  }

  private static boolean headerMagic(ByteBuffer intro) {
    return (intro.get(0) & 0xFF) == 0x8E && (intro.get(1) & 0xFF) == 0xAD
        && (intro.get(2) & 0xFF) == 0xE8;
  }

  /** Where an ISO 9660 image keeps its volume descriptors: sector 16. */
  private static final long ISO_DESCRIPTORS = 16 * 2048L;

  /**
   * An ISO 9660 image's volume creation and modification dates, from its primary volume
   * descriptor. Its files are not listed.
   */
  static Optional<Instant> iso(Path file, Instant cutoff) throws IOException {
    byte[] descriptor = ArchiveDates.read(file, ISO_DESCRIPTORS, 2048);
    if (descriptor.length < 847 || descriptor[0] != 1
        || !"CD001".equals(new String(descriptor, 1, 5, StandardCharsets.US_ASCII))) {
      return Optional.empty();
    }
    for (int offset : new int[] {813, 830}) { // creation, modification
      Optional<Instant> late = isoDate(descriptor, offset).flatMap(at -> ArchiveDates.after(at, cutoff));
      if (late.isPresent()) return late;
    }
    return Optional.empty();
  }

  /**
   * An ISO 9660 date: sixteen ASCII digits (year to hundredths of a second) and an offset from
   * GMT in quarter hours. All zeros, or anything not a date, means none was recorded.
   */
  private static Optional<Instant> isoDate(byte[] descriptor, int offset) {
    String digits = new String(descriptor, offset, 16, StandardCharsets.US_ASCII);
    if (!digits.chars().allMatch(Character::isDigit) || digits.startsWith("0000")) {
      return Optional.empty();
    }
    try {
      LocalDateTime local = LocalDateTime.of(
          Integer.parseInt(digits.substring(0, 4)), Integer.parseInt(digits.substring(4, 6)),
          Integer.parseInt(digits.substring(6, 8)), Integer.parseInt(digits.substring(8, 10)),
          Integer.parseInt(digits.substring(10, 12)), Integer.parseInt(digits.substring(12, 14)));
      ZoneOffset zone = ZoneOffset.ofTotalSeconds(descriptor[offset + 16] * 15 * 60);
      return Optional.of(local.toInstant(zone));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** An unsigned ASCII decimal field, space-padded on the right. */
  private static long decimal(byte[] bytes, int offset, int length) {
    String field = new String(bytes, offset, length, StandardCharsets.US_ASCII).trim();
    return field.isEmpty() ? 0 : Long.parseLong(field);
  }
}
