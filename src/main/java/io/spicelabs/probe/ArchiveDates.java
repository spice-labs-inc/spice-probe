package io.spicelabs.probe;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

/**
 * Whether anything in an archive is dated after a cutoff, read without unpacking it.
 *
 * <p>This is the content half of an artifact cutoff: when the server an artifact came from
 * gives no publication time, the dates its builder wrote into the archive are the next best
 * thing. One entry dated after the cutoff is enough, on the assumption that nobody postdates
 * an entry. Nothing is extracted, and an archive inside an archive is not opened: a jar in a
 * war, or a layer in an image, is only an entry with a date of its own.
 *
 * <p>The format is recognised from the file's first bytes, never its name:
 *
 * <ul>
 *   <li>ZIP and everything built on it (jar, war, ear, aar, apk, whl, nupkg, vsix, .conda, Go
 *       module zips): the central directory, at the end of the file;
 *   <li>tar, uncompressed (and so .gem): every entry header, seeking over the contents;
 *   <li>tar compressed with gzip, bzip2, xz or zstd (.tgz, .crate, npm and Helm tarballs,
 *       sdists): every entry header, which means decompressing what lies between them, within
 *       {@link Limits}; for gzip, also the time in its own header;
 *   <li>7z: the header database;
 *   <li>ar, and so .deb: each member's header;
 *   <li>RPM: the build time in its header;
 *   <li>ISO 9660: the volume's creation and modification dates.
 * </ul>
 *
 * <p>Anything else, an archive too damaged to read, or one that runs out of budget, has no
 * known dates, and the answer is empty: the artifact is analysed. Old placeholder dates
 * (1980-01-01 for reproducible ZIPs, 1985-10-26 for npm tarballs, the epoch for a gzip without
 * a time) need no special treatment, because a cutoff is never earlier than them.
 */
public final class ArchiveDates {

  private ArchiveDates() {}

  /**
   * A date after {@code cutoff} found in {@code file}, or empty if none was found or the file's
   * dates could not be read. Reading stops at the first such date.
   *
   * @param file the archive to read; anything that is not a regular file has no dates
   * @param cutoff the instant an entry must be dated after to count
   * @return the first date after {@code cutoff} found, or empty
   */
  public static Optional<Instant> entryAfter(Path file, Instant cutoff) {
    return entryAfter(file, cutoff, Limits.DEFAULT);
  }

  /**
   * As {@link #entryAfter(Path, Instant)}, within the given limits.
   *
   * @param file the archive to read; anything that is not a regular file has no dates
   * @param cutoff the instant an entry must be dated after to count
   * @param limits how much a compressed tar may cost before its dates count as unknown
   * @return the first date after {@code cutoff} found, or empty
   */
  public static Optional<Instant> entryAfter(Path file, Instant cutoff, Limits limits) {
    if (!Files.isRegularFile(file)) return Optional.empty();
    try {
      byte[] head = head(file, 512);
      return switch (Format.of(head)) {
        case ZIP -> zip(file, cutoff);
        case GZIP -> gzip(file, cutoff, limits);
        case BZIP2, XZ, ZSTD -> compressedTar(file, Format.of(head), cutoff, limits);
        case TAR -> tar(file, cutoff);
        case SEVEN_Z -> sevenZ(file, cutoff);
        case AR -> Headers.ar(file, cutoff);
        case RPM -> Headers.rpm(file, cutoff);
        case UNKNOWN -> Headers.iso(file, cutoff);
      };
    } catch (IOException | RuntimeException | LinkageError e) {
      // Damaged, truncated, over budget, or a codec that cannot load (zstd is native): the
      // dates are unknown, which is not the same as late.
      return Optional.empty();
    }
  }

  private static Optional<Instant> zip(Path file, Instant cutoff) throws IOException {
    try (ZipFile zip = new ZipFile(file.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        Optional<Instant> late = after(entries.nextElement().getLastModifiedTime(), cutoff);
        if (late.isPresent()) return late;
      }
      return Optional.empty();
    }
  }

  private static Optional<Instant> gzip(Path file, Instant cutoff, Limits limits)
      throws IOException {
    try (GzipCompressorInputStream gzip =
        GzipCompressorInputStream.builder()
            .setInputStream(new BufferedInputStream(Files.newInputStream(file)))
            .setDecompressConcatenated(true)
            .get()) {
      // The time the archive was compressed, when the compressor recorded one (the epoch when
      // not, which is never after a cutoff).
      Optional<Instant> late = after(gzip.getMetaData().getModificationInstant(), cutoff);
      if (late.isPresent()) return late;
      return tar(gzip, cutoff, limits);
    }
  }

  private static Optional<Instant> compressedTar(
      Path file, Format format, Instant cutoff, Limits limits) throws IOException {
    InputStream raw = new BufferedInputStream(Files.newInputStream(file));
    InputStream decompressed =
        switch (format) {
          case BZIP2 -> new BZip2CompressorInputStream(raw, true);
          case XZ -> new XZCompressorInputStream(raw, true);
          case ZSTD -> new ZstdCompressorInputStream(raw);
          default -> throw new IllegalArgumentException(format.toString());
        };
    return tar(decompressed, cutoff, limits);
  }

  /**
   * Every entry header of an uncompressed tar, which can be found by seeking over the contents,
   * so no budget is needed. (Reading it as a stream would read the contents instead.)
   */
  private static Optional<Instant> tar(Path file, Instant cutoff) throws IOException {
    try (TarFile tar = new TarFile(file)) {
      for (TarArchiveEntry entry : tar.getEntries()) {
        Optional<Instant> late = after(entry.getLastModifiedTime(), cutoff);
        if (late.isPresent()) return late;
      }
      return Optional.empty();
    }
  }

  /**
   * Every entry header of a decompressed tar stream, within the budget; closes the stream. Not
   * a tar: no dates.
   */
  private static Optional<Instant> tar(InputStream in, Instant cutoff, Limits limits)
      throws IOException {
    try (TarArchiveInputStream tar = new TarArchiveInputStream(new Metered(in, limits))) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        Optional<Instant> late = after(entry.getLastModifiedTime(), cutoff);
        if (late.isPresent()) return late;
      }
      return Optional.empty();
    }
  }

  private static Optional<Instant> sevenZ(Path file, Instant cutoff) throws IOException {
    try (SevenZFile sevenZ = SevenZFile.builder().setPath(file).get()) {
      for (SevenZArchiveEntry entry : sevenZ.getEntries()) {
        if (!entry.getHasLastModifiedDate()) continue;
        Optional<Instant> late = after(entry.getLastModifiedTime(), cutoff);
        if (late.isPresent()) return late;
      }
      return Optional.empty();
    }
  }

  static Optional<Instant> after(FileTime time, Instant cutoff) {
    return time == null ? Optional.empty() : after(time.toInstant(), cutoff);
  }

  static Optional<Instant> after(Instant time, Instant cutoff) {
    return time != null && time.isAfter(cutoff) ? Optional.of(time) : Optional.empty();
  }

  /** Up to {@code n} bytes from the start of {@code file}. */
  static byte[] head(Path file, int n) throws IOException {
    return read(file, 0, n);
  }

  /** Up to {@code n} bytes of {@code file} from {@code position}; fewer at the end of it. */
  static byte[] read(Path file, long position, int n) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      ByteBuffer buffer = ByteBuffer.allocate(n);
      while (buffer.hasRemaining()) {
        int got = channel.read(buffer, position + buffer.position());
        if (got < 0) break;
      }
      byte[] bytes = new byte[buffer.position()];
      buffer.flip().get(bytes);
      return bytes;
    }
  }
}
