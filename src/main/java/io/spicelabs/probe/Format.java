package io.spicelabs.probe;

/** An archive or compression format, recognised by the bytes it starts with. */
enum Format {
  ZIP,
  GZIP,
  BZIP2,
  XZ,
  ZSTD,
  TAR,
  SEVEN_Z,
  AR,
  RPM,
  UNKNOWN;

  static Format of(byte[] head) {
    if (starts(head, 0x50, 0x4B, 0x03, 0x04)
        || starts(head, 0x50, 0x4B, 0x05, 0x06) // an empty archive
        || starts(head, 0x50, 0x4B, 0x07, 0x08)) return ZIP; // a spanned one
    if (starts(head, 0x1F, 0x8B)) return GZIP;
    if (starts(head, 'B', 'Z', 'h')) return BZIP2;
    if (starts(head, 0xFD, '7', 'z', 'X', 'Z', 0x00)) return XZ;
    if (starts(head, 0x28, 0xB5, 0x2F, 0xFD)) return ZSTD;
    if (starts(head, '7', 'z', 0xBC, 0xAF, 0x27, 0x1C)) return SEVEN_Z;
    if (starts(head, '!', '<', 'a', 'r', 'c', 'h', '>', '\n')) return AR;
    if (starts(head, 0xED, 0xAB, 0xEE, 0xDB)) return RPM;
    // POSIX and GNU tar put "ustar" at 257; a pre-POSIX tar has no magic, and is not recognised.
    if (at(head, 257, 'u', 's', 't', 'a', 'r')) return TAR;
    return UNKNOWN;
  }

  private static boolean starts(byte[] bytes, int... expected) {
    return at(bytes, 0, expected);
  }

  private static boolean at(byte[] bytes, int offset, int... expected) {
    if (bytes.length < offset + expected.length) return false;
    for (int i = 0; i < expected.length; i++) {
      if ((bytes[offset + i] & 0xFF) != expected[i]) return false;
    }
    return true;
  }
}
