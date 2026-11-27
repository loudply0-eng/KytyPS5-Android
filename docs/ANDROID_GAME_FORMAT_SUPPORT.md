# Android PS5 Game Format and Driver Support

This document describes what the launcher can recognize today. A file extension alone never proves that a game is installable or bootable.

## Game library formats

| Format | Discovery / validation | Launch status |
|---|---|---|
| Extracted game folder | Recognizes `sce_sys/param.json` or `sce_sys/param.sfo` (case-insensitive); reads title/title ID and resolves `sce_sys/icon0.png` case-insensitively | A visible per-game **Play** button calls the Kyty launch path; actual compatibility remains title/device dependent |
| `.zar` | Listed as a ZArchive image | Passed to the existing archive mount path |
| `.exfat` | Listed as an exFAT image | Passed to the existing exFAT mount path |
| `.img` | Listed only when a bounded content probe confirms a raw, MBR-partitioned, or GPT-partitioned exFAT volume | Passed to the exFAT mount path; arbitrary ISO/raw images are not accepted |
| `.pkg` | Validates PS5 FIH outer-header and PFS/CNT ranges; handles both debug FIH (readable outer-PFS superblock) and retail FIH (encrypted outer PFS) without interpreting retail ciphertext as a superblock. Reads bounded plaintext CNT metadata, `masterDataId`, application version and an unencrypted PNG `icon0.png` when present | **Inspection only — not installable or launchable** |

The game grid displays `icon0.png` when available; when the dump has no usable cover image it displays a title-initial fallback rather than a blank cover. The per-game Play button is enabled only for launchable entries.

For PKGs, the launcher can show the title, PS5 master data ID and cover when those CNT fields are plaintext. The cover is copied into app-private cache; encrypted or malformed image entries are ignored. The inspector displays entry IDs, names when the name table is readable, sizes and the CNT-level encryption flag. Debug FIH images can be structurally checked through their readable outer-PFS superblock. Retail FIH images encrypt that superblock, so the probe validates only the FIH segment geometry and readable CNT metadata; it does not mistake encrypted bytes for a filesystem header. The game payload is stored in the package PFS image. This app still does not decrypt PFS/PFSC payloads, extract game files, install packages, process licences/keys, bypass DRM, or invoke the emulator with a PKG. Retail-package decryption remains unsupported without a legitimately supplied image key. LIH is signature recognition only and is not treated as a validated installable package. Invalid files renamed to `.pkg` are ignored.

Other formats such as ISO, CHD, CSO, ZIP/7z archives, and arbitrary BIN files are not claimed as supported by this change. Each additional format needs its own validated parser/mount path and tests; adding an extension to the scanner is not sufficient.

## Per-game GPU driver selection

Each game's settings now include three driver choices:

- **Auto (GPU-compatible):** selects an already-installed package that the driver's compatibility advisor marks recommended for the detected Adreno generation.
- **Use global:** follows the launcher's global GPU driver setting.
- **System driver or a named imported package:** explicit manual override for that game.

Auto mode is stored separately for each game's path. It does not silently download or install drivers. If GPU detection fails or no installed package is marked recommended, the selection falls back to the global choice. The advisor currently uses GPU-family and package-name/version hints; it is **not** a per-title benchmark database and cannot guarantee that one driver is best for a particular game.

A driver recommendation does not establish Vulkan initialization, successful game boot, or playable performance on a device. Those still require runtime tests on the target phone.
