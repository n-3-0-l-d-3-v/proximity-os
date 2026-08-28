# Proximity OS

Proximity OS is a temporary, local coordination layer that forms
automatically between nearby smartphones. Devices discover each other over
Bluetooth LE, prove who they are, open an encrypted channel, and exchange
data — with no internet, no account, and no server anywhere.

Every action that touches the mesh, in or out, passes through the
**Guardrail Engine**: a first-class, user-controllable policy system.
Nothing sensitive happens without an explicit, explainable decision, and
every decision is written to a local audit log the user can read.

> **Status: early development.** Phase 1 (core connectivity) is
> substantially built and unit-tested, but has not yet been run between two
> physical phones. See [Current state](#current-state) for an honest
> breakdown of what works and what doesn't.

## Why

Existing mesh and offline messengers optimise for reachability and treat
safety as an afterthought. Proximity OS starts from the opposite
assumption: **default deny**, every block explained in plain language, and
every decision recorded where the user can check it.

The test we hold features to is: *would a non-technical person understand
what this app just did, and why?*

## Core principles

1. **Default deny** — anything not explicitly allowed is blocked.
2. **User understanding** — every blocked or sensitive action is explainable in plain language.
3. **No central account required** for basic use.
4. **Battery and privacy first.**
5. **Capability-based** — devices advertise short-lived capabilities, not open access.
6. **Auditability** — every significant decision is logged locally, readably.
7. **Continuous shipping** — small, frequent, verifiable commits.

## Current state

**Working and unit-tested**

- Wire protocol: framing, chunking to the negotiated BLE MTU, reassembly
  with bounded memory and hostile-input handling.
- Cryptography: P-256 ECDH, HKDF-SHA256 (checked against RFC 5869
  vectors), AES-256-GCM with per-direction keys, monotonic nonces, and
  replay rejection.
- Device identity: P-256 ECDSA keypair generated in the Android Keystore;
  the private key is non-extractable.
- Authenticated handshake binding ephemeral keys to long-term identities.
- Guardrail Engine: safety floor, user-configurable rules, category
  defaults, and an audit log entry for every decision.
- `MeshManager`: the single choke point between the radio and the app.
- Bluetooth LE transport: symmetric scan/advertise, GATT client and server,
  serialised writes, MTU negotiation.
- UI: onboarding, nearby devices, chat, audit log, and policy settings.

**Not built yet**

- Multi-hop relay and store-and-forward (messages only travel one hop).
- Durable storage — the audit log, conversations, and trust decisions are
  in memory and are lost when the app is killed.
- File transfer, shared lists, and capability advertisement.
- QR-code verification (fingerprints are compared manually today).
- A foreground service, so the mesh stops when the app is backgrounded.
- Wi-Fi Direct/Aware transport.
- iOS.

**Not yet verified**

The two-device flow has been exercised end to end in an in-memory
integration test, but never over real radios between two physical phones.
Treat BLE behaviour as unproven until it has been.

## Security summary

- Identity keys live in the Android Keystore and cannot be extracted.
- Sessions use ephemeral keys, so past traffic stays protected if a device
  is later compromised.
- The handshake proves the far end holds the private key it claims — but
  **not** who is holding the phone. That gap is closed by comparing the
  short fingerprint in person. Until then the UI marks a peer *unverified*,
  and says why.
- The advertised Bluetooth payload contains no stable, user-identifying
  name, to limit passive tracking.

Details and rationale: [docs/adr/0001-cryptography.md](docs/adr/0001-cryptography.md)
and [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).

## Tech stack

- **Kotlin Multiplatform** for all shared logic: Guardrail Engine, protocol,
  crypto, identity, mesh orchestration, domain models.
- **Compose Multiplatform** (Material 3) for UI, Android first.
- **Android** (primary): Bluetooth LE, Android Keystore, JCA.
- **iOS** (later): Core Bluetooth, same shared core.

No third-party cryptography library — the primitives come from the platform
and the composition (HKDF, transcripts, session handling) is our own shared,
tested code.

## Project layout

```
proximity-os/
├── shared/          # KMP core
│   └── src/commonMain/kotlin/os/proximity/shared/
│       ├── crypto/     # primitives interface, HKDF, SecureSession
│       ├── domain/     # Peer, ChatMessage, Conversation
│       ├── guardrail/  # engine, rules, policy catalog, audit log
│       ├── identity/   # device identity, trust store
│       ├── mesh/       # MeshTransport interface, MeshManager
│       ├── protocol/   # framing, chunking, envelopes
│       └── session/    # handshake, transcripts
├── androidApp/      # Android app
│   └── src/main/kotlin/os/proximity/android/
│       ├── mesh/       # BLE transport
│       └── ui/         # screens, components, theme
└── docs/            # architecture, threat model, policy design, ADRs
```

## Building

Requires JDK 17+ and the Android SDK (Android Studio provides both).

```bash
./gradlew :androidApp:assembleDebug
```

Run the test suites — the shared logic is where the guarantees live:

```bash
./gradlew :shared:testDebugUnitTest
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Threat model](docs/THREAT_MODEL.md)
- [Guardrail policy design](docs/GUARDRAIL_POLICY.md)
- [ADR 0001 — Cryptography](docs/adr/0001-cryptography.md)
- [Changelog](docs/CHANGELOG.md)

## License

TBD.
