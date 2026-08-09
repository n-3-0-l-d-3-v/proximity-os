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
- Verified the scaffold builds and runs on a real Android emulator.

## Phase 1 — Core Connectivity (in progress)

- Added Bluetooth LE permissions for device discovery.
- Added `DefaultGuardrailEngine`: safety floor, user-configurable
  `PolicyRule`s, and category defaults, backed by `InMemoryAuditLog`.
- Added `DeviceIdentity`/`DeviceIdentityProvider` interfaces and a
  Tink-backed Android implementation (Ed25519 keypair, private key held in
  Android Keystore).
- Added `MeshTransport` interface and a Bluetooth LE implementation
  (`BleMeshTransport`): symmetric advertise + scan for discovery, GATT
  client/server pair for basic message exchange.
- Wired permissions, discovery, and connection requests through the
  Guardrail Engine in the UI, including an `AskUser` confirmation dialog
  for unverified peer connections.

Not yet implemented in Phase 1: payload encryption over the BLE channel,
message chunking beyond the negotiated MTU, and multi-hop relay. Payload
encryption in particular has not been tested against a second physical
device yet — flagged here rather than claimed as done.
