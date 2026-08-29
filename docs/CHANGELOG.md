# Changelog

High-level, human-readable log of notable changes. Not every commit is
listed here — this tracks meaningful progress, not every file touched.

## Phase 0 — Foundation

- Initialized repository, README, and `.gitignore`.
- Added architecture overview, threat model skeleton, and Guardrail policy
  design.
- Scaffolded Kotlin Multiplatform + Compose Multiplatform Gradle project
  (`shared`, `androidApp`).
- Added the initial `GuardrailEngine` interface and request/decision model.
- Verified the scaffold builds and runs on an Android emulator.

## Phase 1 — Core Connectivity

**Protocol**

- Wire framing with a 12-byte header, chunking to the negotiated MTU, and
  reassembly that bounds memory, evicts stale partials, ignores duplicate
  chunks, and never throws on malformed input.
- `Envelope` message types (handshake, chat, ack, list operations,
  capability adverts) with a codec that returns null rather than throwing
  on hostile input.

**Cryptography**

- `CryptoPrimitives` — a deliberately small platform surface (RNG,
  SHA-256, HMAC, ECDH, AES-GCM), implemented on Android with JCA.
- HKDF-SHA256 implemented in shared code and checked against RFC 5869
  test vectors.
- `SecureSession`: per-direction keys, monotonic nonces, replay rejection,
  and session-bound associated data.
- Authenticated handshake binding ephemeral keys to long-term identities,
  with length-prefixed transcripts.
- **Replaced Tink with Android Keystore P-256 ECDSA** for device identity,
  so the private key is non-extractable. Rationale in
  `docs/adr/0001-cryptography.md`.

**Guardrail Engine**

- `DefaultGuardrailEngine`: hard-coded safety floor, user-configurable
  rules by priority, category defaults, default-deny fallback.
- `PolicyCatalog` — every user-facing switch in one place, with the plain
  language explanation living next to the rule it produces.
- Audit log made observable so the UI reflects decisions live.

**Mesh**

- `MeshManager`: the single path between the radio and the app. Discovery,
  connection, handshake, encryption, routing, and trust all pass through
  it, and every step consults the Guardrail Engine.
- "Ask me" decisions are surfaced to the UI and genuinely block the mesh
  until the user answers.
- Bluetooth LE transport rewritten with serialised writes (BLE permits one
  outstanding write per connection — the previous version silently dropped
  chunks) and MTU negotiation.

**UI**

- Design system: palette, type scale, shapes, and distinct semantic colours
  for allow / ask / deny.
- Onboarding that states plainly what the app will never do.
- Nearby, Chat, Activity (audit log), and Rules screens.

**Tests**

- Framing, chunking, and hostile-input handling.
- HKDF against published vectors; AEAD tamper, wrong-key, and wrong-AAD
  detection.
- Full handshake including machine-in-the-middle, replay, reflection, and
  malformed-input rejection.
- Two `MeshManager`s over an in-memory transport: handshake, chunked chat,
  delivery acks, the "ask me" flow, and audit coverage.

### Known gaps at the end of this phase

- Nothing is persisted; all state is lost when the app is killed.
- Messages travel one hop only — no relay or store-and-forward.
- Never run between two physical phones; BLE behaviour is unproven.
- No foreground service, so the mesh stops when the app is backgrounded.

## Phase 3 — Practical Features (in progress)

**Shared lists**

- Lists replicate as an operation-based CRDT with last-writer-wins
  registers per field, so concurrent edits to different fields both
  survive. Ordering uses Lamport counters rather than wall-clock time, so a
  device with a wrong clock cannot dominate every conflict. Rationale in
  `docs/adr/0003-shared-lists.md`.
- Deletion is a tombstone, so a peer that missed a delete cannot resurrect
  the item.
- Live edits broadcast as operations; full replicas are exchanged on
  connect so devices that were apart reconcile.
- Convergence is tested as a property over shuffled, duplicated and
  reversed operation streams. This found two real bugs:
  - `ADD` advanced `doneStamp` without writing a `done` value, which
    suppressed earlier edits depending on arrival order.
  - `createdAtEpochMillis` came from whichever operation first materialised
    an item, so replicas sorted the same list differently.
- New `SYNC_LIST` action type, mediated by the Guardrail Engine in both
  directions, plus a user policy to restrict list sharing to verified peers.
- Lists tab with list overview and item detail screens.

**Mesh robustness** (both found by the list integration tests)

- Sealed frames arriving before the local session is ready are now buffered
  and replayed. The responder completes its handshake first and could send
  application data while the initiator was still deriving keys; those
  frames were being dropped permanently.
- Handshake role is now determined by envelope type rather than local
  state. A peer that dropped and reconnected sends a fresh `Hello`, which
  was previously mistaken for a reply to our own in-flight handshake,
  leaving both sides stuck.
