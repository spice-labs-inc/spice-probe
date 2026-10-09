package io.spicelabs.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveDatesTest {

  private static final Instant CUTOFF = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant EARLY = Instant.parse("2025-06-01T12:00:00Z");
  private static final Instant LATE = Instant.parse("2026-03-01T12:00:00Z");
  /** Where reproducible ZIP builders pin every entry. */
  private static final Instant DOS_EPOCH = Instant.parse("1980-01-01T00:00:00Z");

  @TempDir Path dir;

  // ---- ZIP ---------------------------------------------------------------------------------

  @Test
  void aZipWithOneLateEntryIsLate() throws IOException {
    Path zip = zip("app.jar", EARLY, LATE, EARLY);
    assertEquals(Optional.of(LATE), ArchiveDates.entryAfter(zip, CUTOFF));
  }

  @Test
  void aZipWhoseEntriesAreAllEarlyHasNothingLate() throws IOException {
    assertEquals(Optional.empty(), ArchiveDates.entryAfter(zip("app.jar", EARLY, EARLY), CUTOFF));
  }

  @Test
  void aReproducibleZipPinnedTo1980HasNothingLate() throws IOException {
    assertEquals(
        Optional.empty(), ArchiveDates.entryAfter(zip("lib.whl", DOS_EPOCH, DOS_EPOCH), CUTOFF));
  }

  @Test
  void aZipWithOnlyDosTimesIsReadToo() throws IOException {
    // setTime writes the DOS date and time alone, with no UTC extended timestamp.
    Path zip = dir.resolve("dos.zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      ZipEntry entry = new ZipEntry("late.txt");
      entry.setTime(LATE.toEpochMilli());
      out.putNextEntry(entry);
      out.write(1);
      out.closeEntry();
    }
    Optional<Instant> late = ArchiveDates.entryAfter(zip, CUTOFF);
    assertTrue(late.isPresent(), "a DOS time after the cutoff is found");
    // DOS times are local and to 2 seconds: close, not exact, and that is enough.
    assertTrue(Duration.between(late.get(), LATE).abs().compareTo(Duration.ofDays(1)) < 0);
  }

  @Test
  void anEmptyZipHasNothingLate() throws IOException {
    Path zip = dir.resolve("empty.zip");
    new ZipOutputStream(Files.newOutputStream(zip)).close();
    assertEquals(Optional.empty(), ArchiveDates.entryAfter(zip, CUTOFF));
  }

  @Test
  void anEntryDatedInTheFutureCountsAsLate() throws IOException {
    Instant future = Instant.parse("2099-01-01T00:00:00Z");
    assertEquals(Optional.of(future), ArchiveDates.entryAfter(zip("odd.zip", future), CUTOFF));
  }

  @Test
  void aJarInsideAZipIsNotOpened() throws IOException {
    // The inner jar holds a late entry, but it is only an entry here, with an early date.
    Path inner = zip("inner.jar", LATE);
    Path outer = dir.resolve("app.war");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outer))) {
      ZipEntry entry = new ZipEntry("WEB-INF/lib/inner.jar");
      entry.setLastModifiedTime(FileTime.from(EARLY));
      out.putNextEntry(entry);
      out.write(Files.readAllBytes(inner));
      out.closeEntry();
    }
    assertEquals(Optional.empty(), ArchiveDates.entryAfter(outer, CUTOFF));
  }

  // ---- tar ---------------------------------------------------------------------------------

  @Test
  void anUncompressedTarWithALateEntryInTheMiddleIsLate() throws IOException {
    Path tar = tar("pkg.tar", out -> out, EARLY, LATE, EARLY);
    assertEquals(Optional.of(LATE), ArchiveDates.entryAfter(tar, CUTOFF));
  }

  @Test
  void anUncompressedTarWhoseEntriesAreAllEarlyHasNothingLate() throws IOException {
    assertEquals(
        Optional.empty(), ArchiveDates.entryAfter(tar("pkg.tar", out -> out, EARLY), CUTOFF));
  }

  @Test
  void anUncompressedTarIsWalkedBySeekingSoItsSizeCostsNothing() throws IOException {
    // 2 MiB of content before the late entry and a 1 KiB budget: only the headers are read.
    Path tar = dir.resolve("big.tar");
    try (TarArchiveOutputStream out = new TarArchiveOutputStream(Files.newOutputStream(tar))) {
      entry(out, "big.bin", EARLY, new byte[2 * 1024 * 1024]);
      entry(out, "late.txt", LATE, new byte[] {1});
    }
    Limits tiny = new Limits(1024, Duration.ofSeconds(30));
    assertEquals(Optional.of(LATE), ArchiveDates.entryAfter(tar, CUTOFF, tiny));
  }

  @Test
  void aGzippedTarIsReadThroughItsHeaders() throws IOException {
    Path tgz = tar("pkg.tgz", GzipCompressorOutputStream::new, EARLY, LATE);
    assertEquals(Optional.of(LATE), ArchiveDates.entryAfter(tgz, CUTOFF));
  }

  @Test
  void aBzip2TarIsRead() throws IOException {
    Path tbz = tar("pkg.tar.bz2", BZip2CompressorOutputStream::new, EARLY, LATE);
    assertEquals(Optional.of(LATE), ArchiveDates.entryAfter(tbz, CUTOFF));
  }

  @Test
  void anXzTarIsRead() throws IOException {
    Path txz = tar("pkg.tar.xz", XZCompressorOutputStream::new, EARLY, LATE);
    assertEquals(Optional.of(LATE), ArchiveDates.entryAfter(txz, CUTOFF));
  }

  @Test
  void aZstdTarIsRead() throws IOException {
    Path tzst = tar("pkg.tar.zst", ZstdCompressorOutputStream::new, EARLY, LATE);
    assertEquals(Optional.of(LATE), ArchiveDates.entryAfter(tzst, CUTOFF));
  }

  @Test
  void theFormatComesFromTheBytesNotTheName() throws IOException {
    Path misnamed = tar("pkg.zip", GzipCompressorOutputStream::new, LATE);
    assertEquals(Optional.of(LATE), ArchiveDates.entryAfter(misnamed, CUTOFF));
  }

  @Test
  void aGzippedFileCompressedAfterTheCutoffIsLate() throws IOException {
    Path gz = dir.resolve("notes.txt.gz");
    GzipParameters parameters = new GzipParameters();
    parameters.setModificationInstant(LATE);
    try (OutputStream out = new GzipCompressorOutputStream(Files.newOutputStream(gz), parameters)) {
      out.write("not a tar".getBytes(StandardCharsets.US_ASCII));
    }
    assertEquals(Optional.of(LATE), ArchiveDates.entryAfter(gz, CUTOFF));
  }

  @Test
  void aGzippedFileWithNoTimeAndNoTarHasNothingLate() throws IOException {
    Path gz = dir.resolve("notes.txt.gz");
    try (OutputStream out = new GzipCompressorOutputStream(Files.newOutputStream(gz))) {
      out.write("not a tar".getBytes(StandardCharsets.US_ASCII));
    }
    assertEquals(Optional.empty(), ArchiveDates.entryAfter(gz, CUTOFF));
  }

  @Test
  void aCompressedTarThatRunsOutOfBudgetHasNothingLate() throws IOException {
    // 2 MiB of incompressible data before the late entry, and a 1 MiB budget.
    byte[] noise = new byte[2 * 1024 * 1024];
    new Random(1).nextBytes(noise);
    Path tgz = dir.resolve("big.tgz");
    try (TarArchiveOutputStream out =
        new TarArchiveOutputStream(new GzipCompressorOutputStream(Files.newOutputStream(tgz)))) {
      entry(out, "noise.bin", EARLY, noise);
      entry(out, "late.txt", LATE, new byte[] {1});
    }
    Limits small = new Limits(1024 * 1024, Duration.ofSeconds(30));
    assertEquals(Optional.empty(), ArchiveDates.entryAfter(tgz, CUTOFF, small));
    assertEquals(Optional.of(LATE), ArchiveDates.entryAfter(tgz, CUTOFF));
  }

  // ---- the others --------------------------------------------------------------------------

  @Test
  void aDebsMembersAreReadFromTheirArHeaders() throws IOException {
    Path deb = ar("pkg.deb", EARLY, LATE);
    assertEquals(Optional.of(LATE), ArchiveDates.entryAfter(deb, CUTOFF));
    assertEquals(Optional.empty(), ArchiveDates.entryAfter(ar("old.deb", EARLY, EARLY), CUTOFF));
  }

  @Test
  void aSevenZipIsReadFromItsHeaders() throws IOException {
    Path sevenZ = dir.resolve("pkg.7z");
    try (SevenZOutputFile out = new SevenZOutputFile(sevenZ.toFile())) {
      for (Instant at : new Instant[] {EARLY, LATE}) {
        SevenZArchiveEntry entry = new SevenZArchiveEntry();
        entry.setName("file-" + at.getEpochSecond());
        entry.setLastModifiedTime(FileTime.from(at));
        out.putArchiveEntry(entry);
        out.write(new byte[] {1});
        out.closeArchiveEntry();
      }
    }
    assertEquals(Optional.of(LATE), ArchiveDates.entryAfter(sevenZ, CUTOFF));
  }

  @Test
  void anRpmsBuildTimeIsRead() throws IOException {
    assertEquals(Optional.of(LATE), ArchiveDates.entryAfter(rpm("late.rpm", LATE), CUTOFF));
    assertEquals(Optional.empty(), ArchiveDates.entryAfter(rpm("early.rpm", EARLY), CUTOFF));
  }

  @Test
  void anIsosVolumeDatesAreRead() throws IOException {
    assertEquals(
        Optional.of(Instant.parse("2026-03-01T11:00:00Z")),
        ArchiveDates.entryAfter(iso("late.iso", "2026030112000000", 4), CUTOFF)); // UTC+1
    assertEquals(
        Optional.empty(), ArchiveDates.entryAfter(iso("blank.iso", "0000000000000000", 0), CUTOFF));
  }

  @Test
  void anythingElseHasNoDates() throws IOException {
    Path text = Files.writeString(dir.resolve("README"), "just text");
    assertEquals(Optional.empty(), ArchiveDates.entryAfter(text, CUTOFF));
    assertEquals(Optional.empty(), ArchiveDates.entryAfter(dir.resolve("missing.jar"), CUTOFF));
    assertEquals(Optional.empty(), ArchiveDates.entryAfter(dir, CUTOFF));
  }

  @Test
  void aDamagedArchiveHasNoDates() throws IOException {
    Path broken = Files.write(dir.resolve("broken.jar"), new byte[] {0x50, 0x4B, 0x03, 0x04, 9, 9});
    assertEquals(Optional.empty(), ArchiveDates.entryAfter(broken, CUTOFF));
    Path truncated = dir.resolve("truncated.tgz");
    byte[] whole = Files.readAllBytes(tar("whole.tgz", GzipCompressorOutputStream::new, LATE));
    Files.write(truncated, Arrays.copyOf(whole, 12));
    assertEquals(Optional.empty(), ArchiveDates.entryAfter(truncated, CUTOFF));
  }

  // ---- fixtures ----------------------------------------------------------------------------

  private Path zip(String name, Instant... times) throws IOException {
    Path zip = dir.resolve(name);
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      for (int i = 0; i < times.length; i++) {
        ZipEntry entry = new ZipEntry("entry-" + i);
        entry.setLastModifiedTime(FileTime.from(times[i]));
        out.putNextEntry(entry);
        out.write(i);
        out.closeEntry();
      }
    }
    return zip;
  }

  private interface Compressor {
    OutputStream wrap(OutputStream out) throws IOException;
  }

  private Path tar(String name, Compressor compressor, Instant... times) throws IOException {
    Path tar = dir.resolve(name);
    try (TarArchiveOutputStream out =
        new TarArchiveOutputStream(compressor.wrap(Files.newOutputStream(tar)))) {
      for (int i = 0; i < times.length; i++) entry(out, "entry-" + i, times[i], new byte[] {1});
    }
    return tar;
  }

  private static void entry(TarArchiveOutputStream out, String name, Instant at, byte[] content)
      throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(content.length);
    entry.setLastModifiedTime(FileTime.from(at));
    out.putArchiveEntry(entry);
    out.write(content);
    out.closeArchiveEntry();
  }

  private Path ar(String name, Instant... times) throws IOException {
    Path ar = dir.resolve(name);
    try (ArArchiveOutputStream out = new ArArchiveOutputStream(Files.newOutputStream(ar))) {
      for (int i = 0; i < times.length; i++) {
        byte[] content = new byte[] {1, 2, 3}; // odd, so the 2-byte padding is exercised
        out.putArchiveEntry(
            new ArArchiveEntry("member-" + i, content.length, 0, 0, 0100644, times[i].getEpochSecond()));
        out.write(content);
        out.closeArchiveEntry();
      }
    }
    return ar;
  }

  /** The smallest file the RPM reader accepts: a lead, an empty signature, and BUILDTIME. */
  private Path rpm(String name, Instant built) throws IOException {
    ByteBuffer rpm = ByteBuffer.allocate(96 + 16 + 16 + 16 + 4);
    rpm.put(new byte[] {(byte) 0xED, (byte) 0xAB, (byte) 0xEE, (byte) 0xDB}).position(96);
    rpm.put(new byte[] {(byte) 0x8E, (byte) 0xAD, (byte) 0xE8, 1, 0, 0, 0, 0}).putInt(0).putInt(0);
    rpm.put(new byte[] {(byte) 0x8E, (byte) 0xAD, (byte) 0xE8, 1, 0, 0, 0, 0}).putInt(1).putInt(4);
    rpm.putInt(1006).putInt(4).putInt(0).putInt(1); // BUILDTIME, INT32, at 0, one value
    rpm.putInt((int) built.getEpochSecond());
    return Files.write(dir.resolve(name), rpm.array());
  }

  /** An image with nothing but a primary volume descriptor carrying a creation date. */
  private Path iso(String name, String created, int quarterHours) throws IOException {
    byte[] image = new byte[16 * 2048 + 2048];
    int pvd = 16 * 2048;
    image[pvd] = 1;
    System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII), 0, image, pvd + 1, 5);
    System.arraycopy(created.getBytes(StandardCharsets.US_ASCII), 0, image, pvd + 813, 16);
    image[pvd + 813 + 16] = (byte) quarterHours;
    return Files.write(dir.resolve(name), image);
  }
}
