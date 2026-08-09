# Proximity OS

Proximity OS is a temporary, local coordination layer that forms automatically
between nearby smartphones. Devices discover each other over Bluetooth LE and
Wi-Fi Direct/Aware, form a multi-hop mesh, advertise short-lived capabilities,
and exchange data — all without requiring the internet.

Every action that touches the mesh, in or out, passes through the
**Guardrail Engine**: a first-class, user-controllable policy and safety
system. Nothing sensitive happens without an explicit, explainable decision.

> Status: early development (Phase 0 — Foundation). Not usable yet.

## Why

Existing mesh/offline messengers optimize for reachability and treat safety
as an afterthought. Proximity OS starts from the opposite assumption:
**default deny**, with every block explained in plain language, and every
decision recorded in a readable local audit trail.

## Core principles

1. **Default Deny** — anything not explicitly allowed is blocked.
2. **User Understanding** — every blocked or sensitive action is explainable in plain language.
3. **No central account required** for basic use.
4. **Battery and privacy first.**
5. **Capability-based** — devices advertise short-lived capabilities, not open access.
6. **Auditability** — every significant decision is logged locally, in readable form.
7. **Continuous shipping** — small, frequent, verifiable commits over big bang releases.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the system design,
[docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) for the security posture, and
[docs/GUARDRAIL_POLICY.md](docs/GUARDRAIL_POLICY.md) for how the policy engine
works.

## Tech stack

- **Kotlin Multiplatform (KMP)** for shared logic: Guardrail Engine, identity
  and capability system, cryptography wrappers, serialization, mesh routing.
- **Compose Multiplatform** for UI, Material 3 based, Android first.
- **Android** (primary target): Bluetooth LE, Wi-Fi Aware/Direct, foreground
  services where needed.
- **iOS** (later): Core Bluetooth, Multipeer Connectivity, same Compose UI.
- Cryptography via Tink, serialization via kotlinx.serialization, local
  storage via SQLDelight, DI via Koin.

## Project layout

```
proximity-os/
├── shared/           # KMP shared module: Guardrail Engine, identity, crypto, models
├── androidApp/        # Android application (Compose Multiplatform UI)
├── docs/               # Architecture, threat model, policy design, changelog
```

## Development phases

- **Phase 0 — Foundation**: repo, project structure, docs skeleton, Guardrail Engine interface.
- **Phase 1 — Core Connectivity**: discovery, encrypted channel, message passing.
- **Phase 2 — Guardrail Engine**: policy evaluation, audit log, user controls.
- **Phase 3 — Practical Features**: shared lists, file transfer, capabilities, trust.
- **Phase 4 — Hardening & Polish**: battery, reliability, UI polish, onboarding.

## Contributing / working style

This project ships in small, frequent, well-named commits. See
[docs/CHANGELOG.md](docs/CHANGELOG.md) for a running log of notable changes.

## License

TBD.
