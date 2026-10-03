# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 4.0.x   | :white_check_mark: |
| < 4.0   | :x:                |

## Threat Model Overview

ZVER CAMERA is an operational security camera application. The security assumptions depend on:
1. Android OS integrity (Non-rooted, unmodified SELinux enforcing kernel).
2. Hardware-backed KeyStore (TEE / StrongBox) for key isolation.
3. Plausible deniability via symmetric uniform-entropy container formatting.

## Reporting a Vulnerability

If you discover a cryptographic vulnerability, timing side-channel, or forensic residue leak in ZVER CAMERA:

1. **Do NOT open a public GitHub issue.**
2. Send an encrypted report to the maintainer via PGP or secure communication channels.
3. Please include:
   - Reproduction steps or proof-of-concept (PoC) code.
   - Affected device hardware and Android OS / Security Patch level.
   - Potential impact analysis.

Maintainers will respond within 48 hours to validate the report and coordinate responsible disclosure.
