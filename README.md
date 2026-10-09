# Spice Probe

Whether anything in an archive is dated after a cutoff, read without unpacking it.

```java
Optional<Instant> late = ArchiveDates.entryAfter(path, cutoff);
```

It returns a date after `cutoff` found in the file. It returns empty when nothing in the
file is after the cutoff, and also when the file's dates can't be read: an unrecognised
format, a damaged archive, or one that runs out of budget. Reading stops at the first late
date.

This is the content half of a Spice Pass or Spice License **artifact cutoff**. When the
server an artifact came from gives no publication time, the dates its builder wrote into the
archive are the next best thing. The spice CLI's inventory survey and Allspice's registry
scans both use it, so a cutoff means the same thing to each.

## Rules

- One entry dated after the cutoff is enough. Nobody postdates an entry, so a date in the
  future counts as late too.
- Only the top level is read. A jar inside a war, or a layer inside an image, is an entry with
  a date of its own; it isn't opened.
- The format comes from the file's first bytes, never its name.
- Old placeholder dates need no handling, because a cutoff is never earlier than them:
  1980-01-01 for reproducible ZIPs, 1985-10-26 for npm tarballs, the epoch for a gzip with no
  time.

## Formats

| Format | What is read | Cost |
|---|---|---|
| ZIP: jar, war, ear, aar, apk, whl, nupkg, vsix, `.conda`, Go module zips | the central directory | a directory at the end of the file |
| tar, uncompressed (and so `.gem`) | every entry header, seeking over the contents | headers only |
| tar under gzip, bzip2, xz or zstd: `.tgz`, `.crate`, npm and Helm tarballs, sdists | every entry header; for gzip, also its own header time | decompression, within `Limits` |
| 7z | the header database | small |
| ar, and so `.deb` | each member's header | headers only |
| RPM | `BUILDTIME` from the header | a few KB |
| ISO 9660 | the volume creation and modification dates | 2 KB |

A compressed tar has no index, so its headers can only be found by decompressing what lies
between them. `Limits.DEFAULT` allows 256 MiB of decompressed data or 30 seconds per archive.
An archive that runs out has no known dates, and is analysed.

## Building

```
./mvnw verify
```

The wrapper pins Maven 3.9.15: Maven 3.10 puts local-repository files into the Maven
Central bundle, and Central rejects it.

Java 21. It depends on commons-compress, xz and zstd-jni, at the versions Goat Rodeo and
Allspice already ship.
