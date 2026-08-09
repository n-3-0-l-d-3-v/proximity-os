# Architecture

## Overview

Proximity OS is layered so that the riskiest code (transport, radios) is
farthest from the UI, and nothing can reach the transport layer, or return
data from it to the UI, without passing through the Guardrail Engine.

```
┌─────────────────────────────────────────────┐
│          Compose Multiplatform UI            │
│     (Polished, modern, Material 3 based)     │
└─────────────────────┬───────────────────────┘
                       │
┌─────────────────────▼───────────────────────┐
│              Guardrail Engine                │
│  • Policy evaluation                         │
│  • Sandboxing / isolation                    │
│  • Readable audit logging                    │
│  • Plain-language explanations               │
└─────────────────────┬───────────────────────┘
                       │
┌─────────────────────▼───────────────────────┐
│         Capability & Identity Layer          │
│  • Cryptographic device identities           │
│  • Short-lived capabilities                  │
│  • Permission / capability tokens            │
└─────────────────────┬───────────────────────┘
                       │
┌─────────────────────▼───────────────────────┐
│           Transport & Mesh Layer             │
│  • Bluetooth LE + Wi-Fi Direct/Aware         │
│  • Multi-hop forwarding                      │
│  • Store-and-forward                         │
│  • Link quality scoring                      │
└─────────────────────────────────────────────┘
```

## Module boundaries

- **`shared`** (Kotlin Multiplatform): all platform-agnostic business logic.
  - `guardrail/` — policy model, evaluator, audit log, explanation generation.
  - `identity/` — device keypairs, identity documents, capability tokens.
  - `crypto/` — thin wrappers around Tink primitives (AEAD, signatures, key agreement).
  - `mesh/` — routing tables, store-and-forward queue, link scoring — platform-agnostic parts only.
  - `model/` — shared domain models (messages, lists, capabilities) and kotlinx.serialization schemas.
- **`androidApp`**: Android-specific transport implementations (BLE, Wi-Fi
  Aware/Direct via `android.net.wifi.aware` / `WifiP2pManager`), foreground
  service for mesh participation, and the Compose Multiplatform UI shell.
- **iOS app** (later): Core Bluetooth / Multipeer Connectivity transport
  implementations behind the same `expect`/`actual` transport interface, same
  Compose Multiplatform UI.

## Data flow rule

Every inbound frame from the transport layer and every outbound action
initiated by the UI or shared logic passes through
`GuardrailEngine.evaluate(request): Decision` before it is acted on. There is
no code path that bypasses this — transport implementations do not expose raw
sockets/channels to UI or application code directly; they only feed the
mesh layer, which only feeds the Guardrail Engine.

## Why KMP + Compose Multiplatform

Business logic (policy, crypto, identity, routing decisions) is identical in
intent across Android and iOS; only the radio APIs differ. Sharing that logic
in Kotlin avoids re-implementing (and re-auditing) the Guardrail Engine twice.
Compose Multiplatform lets the UI be shared too, with platform-specific
adjustments where needed (e.g. background execution model differences).

## Status

This document describes the target architecture. Implementation is in
Phase 0 — only the module skeletons and the `GuardrailEngine` interface
currently exist. See the [README](../README.md) for phase status.
