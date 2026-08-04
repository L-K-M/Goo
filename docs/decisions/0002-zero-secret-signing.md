# 0002 — Zero-secret signing with a checked-in debug keystore

- **Status:** accepted
- **Date:** 2026-08-04

## Context

Release APKs must be signed. The family offers two proven models: a real
keystore held in four CI secrets (sibling Blipbird — store-capable,
fail-closed release gates) or a checked-in debug keystore signing both
build types (sibling Kararead — zero secrets, anyone can build
upgrade-compatible APKs). Goo is a fun sideload app distributed via GitHub
Releases, not app stores; its CI and releases are operated by agents that
cannot mint or hold secrets. The choice is upgrade-compatibility-critical:
Android refuses to upgrade an install whose signature changes, so the model
must be picked before the first release, not after.

## Decision

The checked-in `app/debug.keystore` (standard android/androiddebugkey
passwords, generated fresh for this repo) signs BOTH debug and release
build types. No signing secrets exist anywhere. `.gitignore` whitelists
exactly this file; the debug variant uses the `.debug` applicationIdSuffix
so both builds coexist on a device.

## Consequences

- CI publishes installable releases with zero configuration; every clone
  builds APKs that can upgrade an existing install.
- The signature proves nothing about origin (the key is public). Sideload
  trust rests on the GitHub Release provenance, stated in SECURITY.md.
- App-store distribution is off the table until a real key is introduced —
  and doing so breaks upgrades for every installed user (uninstall/
  reinstall). That switch is a product decision requiring its own ADR and
  a major-version release.
- Revisit if: store distribution is ever wanted, or Android policy changes
  around debug-signed installs.
