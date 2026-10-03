# ZVER CAMERA (v4.0 Hardened Release)

> **Zero-Telemetry, Cryptographically Plausible-Deniable Android Camera & Digital Vault**  
> Tailored & Benchmarked on MediaTek Helio G85 (MT6768) running Android 15 (API 35).

---

## 🎯 Architecture Overview

ZVER CAMERA is an operational security (OPSEC) camera application designed for human rights defenders, investigative journalists, and privacy engineers working in adversarial physical environments.

Unlike conventional camera applications that leak metadata through `MediaStore`, cloud thumbnails, system caches, and aggressive vendor post-processing, ZVER CAMERA streams directly from the hardware Camera HAL into an encrypted, in-memory pipeline.

---

## ⚙️ Hardware Specifications & HAL Constants

Tested and validated via `dumpsys media.camera` on Xiaomi Redmi 13C (MT6768):

| Parameter | Main Sensor (ID 0) | Front Sensor (ID 1) | Macro Sensor (ID 2) |
|---|---|---|---|
| **Sensor Hardware** | Samsung ISOCELL JN1 (S5KJN1) | OmniVision 8MP | 2MP Fixed Focus |
| **Native Frame Size** | `4080 × 3072` (12.5 MP Quad-Bayer) | `3296 × 2480` (8.0 MP) | `1600 × 1200` (**LOCKED OUT**) |
| **Noise Reduction** | `NOISE_REDUCTION_MODE_OFF` (0) | Standard | N/A |
| **Minimum Focus Dist.** | `10.0` Diopters (10 cm) | Fixed (0.0) | N/A |
| **CameraX Pipeline** | Zero-Copy YUV/JPEG Stream | Standard | Hard-Blocked in Code |

---

## 🔐 Cryptographic Architecture (`MonoVaultEngine.kt`)

### 1. VeraCrypt Monolith Volume (Zero Plaintext Signatures)
* **Container Size:** Fixed 1024 MB single binary file (`storage_block.bin`).
* **Symmetric Partitions:**
  * **Decoy Partition:** Offset `0L` (Ceiling 512 MB).
  * **Master Partition:** Offset `536870912L` (Ceiling 512 MB).
* **Entropy Guarantee:** Zero open magic markers (`VMON`, `GCF1`, `RIFF` completely eliminated). Block headers (48 bytes) are authenticated and encrypted with `AES-256-GCM`. To any forensic utility (`binwalk`, `hexdump`, `strings`), the entire file is mathematically indistinguishable from `/dev/urandom`.

### 2. Block Replay & Tamper Protection (AAD Binding)
Every block is encrypted with `AES-256-GCM` using Additional Authenticated Data:
$$\text{AAD} = \text{fileOffset (8B)} \parallel \text{timestamp (8B)} \parallel \text{zoneId (4B)} \parallel \text{sequenceIndex (4B)}$$
If an adversary attempts to swap blocks or replay an older photo over a newer slot, GCM verification immediately raises `AEADBadTagException`.

### 3. Hardware ECDSA Signature (TEE KeyStore)
Every pixel payload is signed by a hardware-backed `secp256r1` ECDSA key stored in the Android KeyStore StrongBox/TEE. Tampered bytes fail digital signature verification upon read.

---

## 📐 Computational Optics (`AntiBlurSensorEngine.kt`)

* **1:1 Center ROI (400×400):** Unscaled native sensor crop evaluated directly from the 8-bit Luminance (Y) plane in 1–2 ms.
* **Noise-Suppressed Discrete Laplacian:** When `NOISE_REDUCTION_MODE_OFF` is active, high-ISO CMOS shot noise artificially inflates standard Laplacian variance. A 3×3 low-pass smoothing stage filters out salt-and-pepper shot noise before computing edge gradients.
* **Document Scanner Mode:** Virtual level calculated from real-time accelerometer vectors. A neon-green HUD reticle locks when the optical plane is parallel to a flat document ($\theta < 3.0^\circ$).
* **Focus Bracketing:** Rapid 3-shot burst with micro-focus shift, automatically securing the single frame with the highest sharpness index.

---

## 🛡️ Threat Model: Capabilities & Limitations

### ✅ What ZVER CAMERA Protects Against 100%:
1. **Cold Boot & Forensic Storage Extraction:**
   * An adversary inspecting the device flash via chip-off, ADB dump, or forensic software (e.g., Cellebrite, FTK) finds only uniform high-entropy data. The existence of the hidden Master zone is deniable.
2. **Block Swapping & Replay Attacks:**
   * Cryptographic AAD prevents block tampering or rearranging inside the volume.
3. **OSINT Leaks & Cloud Telemetry:**
   * The core application process is compiled with `<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />`. Zero network access.
   * `FLAG_SECURE` prevents screenshots, screen recordings, and Recents menu caching.
   * Zero writes to `MediaStore` or public shared storage.
4. **PanicKit Wiping (Anti-Spoofing & Constant-Time):**
   * Constant-time comparison (`MessageDigest.isEqual`) blocks side-channel timing attacks.
   * KeyStore TEE keys are revoked immediately, rendering all ciphertexts permanently unrecoverable before storage zeroing completes.

### ❌ What ZVER CAMERA CANNOT Protect Against:
1. **Coerced Unlock of Running App:** If an adversary forces the user under duress to unlock the Main PIN while the device is in hand, the app cannot prevent disclosure of the unlocked partition (users should enter the Decoy PIN instead).
2. **Physical Surveillance / Shoulder Surfing:** Optical observation of the screen from behind the user is outside software control (use Stealth Blackout mode).
3. **Compromised Kernel / Hardware Trojan:** If the Android OS kernel has been modified below the TEE or rootkit firmware is installed, software memory isolation cannot be guaranteed.

---

## 📜 License
Apache License 2.0. Built for defensive human rights and independent investigative journalism.
