# Android PS5 Game Format and Driver Support

This document describes what the launcher can recognize today. A file extension alone never proves that a game is installable or bootable.

## Game library formats

| Format | Discovery / validation | Launch status |
|---|---|---|
| Extracted game folder | Recognizes a folder containing `sce_sys/param.json`; reads title and title ID, with `param.sfo` fallback | Passed to the existing Kyty launch path; actual game compatibility remains title/device dependent |
| `.zar` | Listed as a ZArchive image | Passed to the existing archive mount path |
| `.exfat` | Listed as an exFAT image | Passed to the existing exFAT mount path |
| `.img` | Listed only when a bounded content probe confirms a raw, MBR-partitioned, or GPT-partitioned exFAT volume | Passed to the exFAT mount path; arbitrary ISO/raw images are not accepted |
| `.pkg` | Validates PS5 FIH outer-header, PFS/CNT ranges and plaintext CNT metadata; reads unencrypted `param.json` when present | **Metadata only — not installable or launchable** |

The PKG reader does not decrypt PFS/PFSC payloads, decrypt encrypted metadata, extract package files, install packages, process licences/keys, bypass DRM, or invoke the emulator with a PKG. LIH is signature recognition only and is not treated as a validated installable package. Invalid files renamed to `.pkg` are ignored.

Other formats such as ISO, CHD, CSO, ZIP/7z archives, and arbitrary BIN files are not claimed as supported by this change. Each additional format needs its own validated parser/mount path and tests; adding an extension to the scanner is not sufficient.

## Per-game GPU driver selection

Each game's settings now include three driver choices:

- **Auto (GPU-compatible):** selects an already-installed package that the driver's compatibility advisor marks recommended for the detected Adreno generation.
- **Use global:** follows the launcher's global GPU driver setting.
- **System driver or a named imported package:** explicit manual override for that game.

Auto mode is stored separately for each game's path. It does not silently download or install drivers. If GPU detection fails or no installed package is marked recommended, the selection falls back to the global choice. The advisor currently uses GPU-family and package-name/version hints; it is **not** a per-title benchmark database and cannot guarantee that one driver is best for a particular game.

A driver recommendation does not establish Vulkan initialization, successful game boot, or playable performance on a device. Those still require runtime tests on the target phone.
