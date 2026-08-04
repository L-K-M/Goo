# Contributing

Read first, in order: [PLAN.md](PLAN.md) (what and why), [AGENTS.md](AGENTS.md)
(how this repo operates), [CICD.md](CICD.md) (pipelines).

## Ground rules

- **Versions live in `gradle/libs.versions.toml` only.** No ad-hoc pins in
  build files; no version numbers restated in docs.
- **Pure cores stay pure.** `engine/core` is JVM-only Kotlin: no Android
  imports, injectable time/randomness, exhaustively unit-tested. UI and GL
  layers stay thin.
- **No secrets in the repo.** The checked-in debug keystore is the sole,
  documented exception (docs/decisions/0002).
- **No new permissions.** Especially not INTERNET (PLAN.md §5.2). A
  permission is a product decision → ADR first.
- Lint and tests are hard gates; `./gradlew testDebugUnitTest lintDebug`
  must pass before pushing.
- Branches: `claude/<topic>` or `fable/<topic>`; merge commits titled
  `Merge PR #NN: …` (the history convention).

## Architecture decisions

Significant, hard-to-reverse choices get a numbered ADR in
`docs/decisions/` (template there). An ADR records context, the decision,
and consequences — including what would make us revisit it.

## Review loop

Every PR is auto-reviewed by GLM 5.2. Maintainers (usually agents) triage
each finding per [CLAUDE.md](CLAUDE.md): apply, decline with recorded
reasons (in [REVIEW.md](REVIEW.md)), or refute with evidence. Don't
flip-flop on recorded declines without genuinely new evidence.
