# Ghost Camera Security Architecture & Threat Model (v2.1)

## Overview
Ghost Camera is an air-gapped, zero-telemetry camera and cryptographic vault built for high-risk threat environments.

---

## Threat Model: What Ghost Camera Protects Against

### 1. Remote Surveillance & Exfiltration (Network Air-Gap)
- **Mechanisms:**
  - `INTERNET` and `ACCESS_NETWORK_STATE` permissions are physically removed from the merged APK manifest using `tools:node="remove"`.
  - Process isolation: The core camera operates strictly in the `:core` process. Any optional assist modules are segregated into `:assist` with visual perimeter warnings (Red Neon Indicator).
- **Protection:** Prevents malware, third-party SDKs, or background processes from exfiltrating photos or metadata over cellular or Wi-Fi networks.

### 2. Forensic Metadata Analysis (Byte-Level EXIF Stripping)
- **Mechanisms:**
  - CameraX JPEG buffers are parsed at the byte level in RAM without allocating intermediate uncompressed `Bitmap` rasters.
  - Strips all `APP0` through `APP15` segments (EXIF, GPS, JFIF, XMP, ICC, IPTC) and `COM` (Comments).
  - Preserves strictly structural quantization tables (`DQT`), Huffman tables (`DHT`), frame headers (`SOF`), and scan data (`SOS`).
- **Protection:** Eliminates device serial numbers, lens models, GPS geolocation, software build hashes, and timestamps from image data.

### 3. Flash Memory Remanence & Wear-Leveling Forensics (Crypto-Shredding)
- **Mechanisms:**
  - Modern UFS and eMMC NAND flash memory use Flash Translation Layers (FTL) and wear leveling, rendering physical software overwriting (`shred`/`zero`) non-deterministic across spare blocks.
  - Ghost Camera implements **Hardware TEE Key Destruction**: Panic triggers execute `KeyStore.deleteEntry()`, instantly destroying the AES-256 key in the hardware processor secure enclave.
- **Protection:** All `.gcf` files stored on flash storage become mathematically indistinguishable from random noise in microseconds, rendering physical NAND chip-off forensics useless.

### 4. Coercion & Duress Extraction (VeraCrypt-Style Dual Vault)
- **Mechanisms:**
  - Independent cryptographic vaults: `vault_main` and `vault_decoy`.
  - Master PIN derives the KEK to unwrap `wrapped_main.key`.
  - Decoy PIN derives the KEK to unwrap `wrapped_decoy.key`.
  - When unlocked with the Decoy PIN, the application displays a genuine, functioning vault populated with harmless decoy photos.
- **Protection:** Plausible deniability during inspection or forced unlocking.

### 5. Memory Scraping & Heap Dumps
- **Mechanisms:**
  - PIN codes are never converted into immutable `java.lang.String` objects in memory.
  - Handled strictly as `CharArray` and zero-wiped (`fill('0')`) in `finally` blocks immediately after cryptographic derivation.
  - Key derivation uses PBKDF2WithHmacSHA256 with 100,000 iterations and a 16-byte cryptographically secure salt.

### 6. System Leaks & UI Spying
- **Mechanisms:**
  - `FLAG_SECURE` blocks system screenshots, video screen recording, and Recent Apps thumbnail caching.
  - MediaStore scanner isolation via internal `context.filesDir` storage and `.nomedia` sentinels.
  - Keyboard isolation: On-screen PIN pad bypasses system IMEs, prevents clipboard leaks, and disables autofill services.

---

## Threat Model: What Ghost Camera Does NOT Protect Against

No mobile software can defend against threats originating below its privilege level:
1. **Compromised OEM Firmware / Bootloader Rootkits:** If the operating system kernel, camera HAL driver, or baseband is compromised by a state-grade implant (e.g. Pegasus), raw sensor frames can be tapped before reaching user space.
2. **Physical Optical Surveillance:** External cameras pointing at the phone screen while photos are actively being viewed.
3. **Active Physical Coercion of Master Credentials:** If the user voluntarily inputs the Master PIN under coercion rather than the Decoy PIN.
4. **Physical RAM Freezing (Cold-Boot Attack):** Physical extraction and cooling of DRAM chips immediately following device power-off while session keys reside in volatile registers.

---

## PanicKit Integration
Ghost Camera supports the open PanicKit standard (`info.guardianproject.panic.action.TRIGGER`). External trigger applications (such as Ripple or Courier) can broadcast the trigger intent to execute instantaneous TEE key destruction.
