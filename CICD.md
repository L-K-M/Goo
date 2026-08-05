# CI/CD

Every workflow carries the family contract: least-privilege `permissions:`,
an explicit `concurrency:` group, `timeout-minutes:` on every job, and
`gradle/actions/wrapper-validation` before anything executes the wrapper
(supply-chain gate against a tampered `gradle-wrapper.jar`).

## Workflows

### ci.yml — CI
- **Triggers:** push to `main`, every PR, manual dispatch.
- **Permissions:** `contents: read`.
- **Concurrency:** `ci-${{ github.ref }}`; cancels superseded **PR** runs
  only — never an in-progress `main` run, because a permanently "cancelled"
  main commit masks breakage and breaks bisection.
- **Job `android`** (45 min cap): checkout → wrapper-validation → Temurin
  JDK 17 → setup-gradle → `./gradlew testDebugUnitTest lintDebug
  assembleDebug` → upload `goo-debug-<sha>` APK artifact (14-day retention,
  `if-no-files-found: error`).
- No Android SDK install step: the ubuntu runner image ships the SDK, and
  AGP resolves `compileSdk android-37.0` against it (paired with
  `android.suppressUnsupportedCompileSdk` in `gradle.properties`). If the
  runner image ever drops the SDK, add `android-actions/setup-android`.

### release.yml — Release
- **Trigger:** pushed `v*` tag. **Permissions:** `contents: read` by default;
  only the publish job receives `contents: write`.
- **Concurrency:** no cancellation — a half-cancelled publish is worse than
  a slow one.
- Two-job trust split: a read-only build job checks out without persisted
  credentials, validates the wrapper, enforces the tag↔versionName gate,
  re-proves `testDebugUnitTest lintDebug`, runs `assembleRelease`, and uploads
  `dist/goo-vX.Y.Z.apk` + `.sha256`. A separate write-capable publish job
  downloads only those artifacts and creates the GitHub Release with generated
  notes. Every action in this privileged workflow is pinned to a verified
  immutable commit. `vX.Y.Z-rc.1`-style tags auto-mark as pre-release.
- **Signing:** both build types use the checked-in `app/debug.keystore`
  (see `docs/decisions/0002`). No signing secrets exist. Sideload-only by
  design; a future switch to a real key breaks upgrades for every
  installed user and must be treated as a product decision.

### zai-code-review.yml — GLM 5.2 PR Review
- **Trigger:** `pull_request_target` (opened, reopened, synchronize,
  ready_for_review) — secrets are available to the job, hence the guards.
- **Guards:** runs only for non-draft PRs whose head repo IS this repo —
  fork PRs never see the secret or the write-capable token. The action is
  pinned to an immutable commit (`7d0ce7b` = v0.0.9 of
  `L-K-M/zai-code-review`); verify SHA↔tag before bumping:
  `git ls-remote https://github.com/L-K-M/zai-code-review refs/tags/v0.0.9`.
- **Concurrency:** keyed on the PR number (for `pull_request_target`,
  `github.ref` is the base branch and would collide across PRs); superseded
  reviews of an outdated diff are cancelled.
- **Graceful degradation:** if the `ZAI_API_KEY` secret is absent the job
  logs a skip and stays green.
- **Trust boundary:** the guard is same-repo, not admin-only — anyone with
  push access effectively hands `ZAI_API_KEY` and a write token to the
  pinned action. That is why the commit pin matters.

## Secrets

| Secret | Used by | Purpose |
| ------ | ------- | ------- |
| `ZAI_API_KEY` | zai-code-review.yml | Z.ai API key for GLM 5.2 reviews. Set with `gh secret set ZAI_API_KEY --repo L-K-M/Goo`. Absent ⇒ reviews skip, everything else unaffected. |

No release-signing secrets exist, deliberately (decision 0002).

## Dependabot

Weekly `github-actions` and `gradle` update PRs. `gradle/actions` major
updates are ignored with a recorded reason (v6 relicensed its caching
component under a proprietary ToU; we stay on fully-open v5). Gradle
updates are grouped (`androidx`, `kotlin`+`ksp`) so toolchain-coupled bumps
land as one PR.

## Troubleshooting

| Symptom | Likely cause / fix |
| ------- | ------------------ |
| `wrapper-validation` fails | `gradle-wrapper.jar` doesn't match an official checksum — restore it from a trusted clone; do not "update" it to make CI pass. |
| CI can't find compileSdk / platform | Runner image changed. Add `android-actions/setup-android` to the job, or bump the pinned platform. |
| Release job fails at the version gate | Tag was created by hand or on the wrong commit. Delete the tag and re-cut with `scripts/release.sh X.Y.Z --push`. |
| "works in debug, breaks in release" | Missing R8 keep rule — `app/proguard-rules.pro`, see AGENTS.md. |
| Review workflow skipped on a PR | Draft PR, fork PR (by design), or `ZAI_API_KEY` unset. |
