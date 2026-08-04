# Security

## Reporting

Please report vulnerabilities privately via GitHub's "Report a
vulnerability" (Security → Advisories) on this repository. No bounty
program; reports are read and acted on.

## Scope and posture

- The app is fully offline: no INTERNET permission, no accounts, no
  telemetry. The attack surface is essentially malformed image files and
  the usual local-app concerns.
- Image decoding uses the platform `BitmapFactory`/`ImageDecoder` — OS
  hardening applies; we never ship our own codecs.
- The checked-in `app/debug.keystore` is public **by design** (zero-secret
  reproducible builds, sideload-only distribution — docs/decisions/0002).
  APKs signed with it prove nothing about origin; installing them is a
  personal trust decision, same as any sideload. Do not report the public
  keystore as a leak; it is documented, deliberate, and rotation is a
  known-cost product decision.
- CI runs with least-privilege tokens; the one privileged workflow
  (`pull_request_target` review) is fork-guarded and pinned to an immutable
  action commit — see [CICD.md](CICD.md).
