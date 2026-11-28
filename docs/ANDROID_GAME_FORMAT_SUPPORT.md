# Android PS5 Game Format and Driver Support

This document describes what the launcher can recognize today. A file extension alone never proves that a game is installable or bootable.

## Game library formats

| Format | Discovery / validation | Launch status |
|---|---|---|
| Extracted game folder | Recognizes `sce_sys/param.json` or `sce_sys/param.sfo` (case-insensitive); reads title/title ID and resolves `sce_sys/icon0.png` case-insensitively | A visible per-game **Play** button calls the Kyty launch path; actual compatibility remains title/device dependent |
| `.zar` | Listed as a ZArchive image | Passed to the existing archive mount path |
| `.exfat` | Listed as an exFAT image | Passed to the existing exFAT mount path |
| `.img` | Listed only when a bounded content probe confirms a raw, MBR-partitioned, or GPT-partitioned exFAT volume | Passed to the exFAT mount path; arbitrary ISO/raw images are not accepted |
| `.pkg` | Validates PS5 FIH/CNT bounds; parses readable Debug superblock metadata; reads plaintext CNT metadata; for the explicit Debug/Homebrew no-auth outer-PFS layout, maps NAPS ranges and can inspect inner PFS metadata when required ranges are stored | **Read-only inspection — not installable or launchable** |

The game grid displays `icon0.png` when available; when the dump has no usable cover image it displays a title-initial fallback rather than a blank cover. The per-game Play button is enabled only for launchable entries.

For PKGs, the launcher can show the title, PS5 master data ID and cover when those CNT fields are plaintext. The cover is copied into app-private cache; encrypted or malformed image entries are ignored. The inspector displays entry IDs, names when the name table is readable, sizes and the CNT-level encryption flag. The FIH signed byte (`0x00` Debug / `0x80` Retail) is not a PFS mode bit: only a successfully parsed Debug superblock supplies a PFS encryption state, while the Retail superblock and its encryption state remain unknown here. A bounded Debug/Homebrew outer-PFS reader accepts only the explicit `PPRPLAIN-NOAUTH!` seed and supported data-first layout; it enumerates `uroot`, locates `naps_pkg_layout.dat` and `pfs_image.dat`, and builds a conservative NAPS map. The mapped inner-PFS reader can traverse `uroot` and read small metadata files such as `sce_sys/param.json` and `sce_sys/param.sfo` directly from stored, zero-hole and alias ranges without creating a temporary full-image file. When either metadata file is readable, the inspector can show the inner title, title ID and application version. Reads that intersect Kraken-compressed NAPS blocks are refused because Kraken decoding is not implemented. This is still read-only inspection: general NAPS dialect selection, Kraken/PFSC decoding, Retail decryption, full extraction, package installation, licence/key processing, DRM circumvention and direct PKG launch are not implemented. Retail-package decryption remains unsupported unless a legitimate authorized key path is implemented.

Other formats such as ISO, CHD, CSO, ZIP/7z archives, and arbitrary BIN files are not claimed as supported by this change. Each additional format needs its own validated parser/mount path and tests; adding an extension to the scanner is not sufficient.

## Per-game GPU driver selection

Each game's settings now include three driver choices:

- **Auto (GPU-compatible):** selects an already-installed package that the driver's compatibility advisor marks recommended for the detected Adreno generation.
- **Use global:** follows the launcher's global GPU driver setting.
- **System driver or a named imported package:** explicit manual override for that game.

Auto mode is stored separately for each game's path. It does not silently download or install drivers. If GPU detection fails or no installed package is marked recommended, the selection falls back to the global choice. The advisor currently uses GPU-family and package-name/version hints; it is **not** a per-title benchmark database and cannot guarantee that one driver is best for a particular game.

A driver recommendation does not establish Vulkan initialization, successful game boot, or playable performance on a device. Those still require runtime tests on the target phone.
