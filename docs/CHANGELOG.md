# Changelog

High-level, human-readable log of notable changes. Not every commit is
listed here — this tracks meaningful progress, not every file touched.

## Phase 0 — Foundation

- Initialized repository, README, and `.gitignore`.
- Added architecture overview (`docs/ARCHITECTURE.md`).
- Added threat model skeleton (`docs/THREAT_MODEL.md`).
- Added Guardrail Engine policy design (`docs/GUARDRAIL_POLICY.md`).
- Scaffolded Kotlin Multiplatform + Compose Multiplatform Gradle project
  structure (`shared`, `androidApp`).
- Added initial `GuardrailEngine` interface and request/decision model in
  `shared`.
