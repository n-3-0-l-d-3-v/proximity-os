# Threat Model

Living document. Update whenever a new feature introduces a new attack
surface, or a new attack is identified against an existing one.

## Assets

- Message and file contents exchanged over the mesh.
- Device identity keys (long-term and ephemeral).
- Capability tokens (what a device is currently allowed to do / request).
- The local audit log (evidence of what happened and why).
- Location and presence information (who is near whom, when).
- Battery / availability of the device as a mesh participant.

## Trust boundaries

- Any other device in Bluetooth/Wi-Fi range is untrusted until its identity
  is verified (first contact = unverified).
- A verified peer is trusted only for the capabilities it was explicitly
  granted, and only for the lifetime of that grant.
- Multi-hop relays are not trusted with plaintext of traffic they forward —
  routing must not require relays to decrypt payloads.

## Threats considered

### 1. Malicious nearby peer
A device in range that participates in discovery/mesh but sends malformed,
oversized, or malicious frames, or attempts actions outside its granted
capabilities.
- **Mitigation**: all inbound frames are parsed defensively and evaluated by
  the Guardrail Engine before any effect; default-deny for anything not
  explicitly permitted; size/rate limits enforced at the transport boundary.

### 2. Capability spoofing
A device claims a capability (e.g. "verified contact", "relay") it was not
actually granted, or replays an old capability token.
- **Mitigation**: capability tokens are cryptographically signed, short-lived,
  and scoped to a specific device identity; replay is mitigated with
  timestamps/nonces and expiry enforced by the Guardrail Engine, not by the
  claimant.

### 3. Sybil / identity attacks
One attacker creates many device identities to appear as multiple trusted
peers, inflate routing influence, or evade per-identity rate limits.
- **Mitigation**: identities are cheap by design (no central account), so
  Sybil resistance comes from behavior-based trust (verified-in-person /
  "I know this person" checks) rather than identity scarcity. Routing and
  capability decisions weight verified identities over unverified ones.

### 4. Bluetooth fingerprinting and tracking
Static BLE identifiers (MAC, advertised name, GATT characteristics) let a
third party track a device's movement over time without participating in the
mesh.
- **Mitigation**: use platform-supported MAC randomization; avoid static,
  globally unique identifiers in advertisements; rotate ephemeral
  advertising identifiers independent of the long-term identity key.

### 5. Data exfiltration attempts
A compromised or malicious app component (or a peer tricking the Guardrail
Engine) tries to move local data (contacts, location, files) out to a peer
or, once connectivity returns, to the internet.
- **Mitigation**: default-deny on access to contacts/calendar/location;
  Guardrail Engine mediates every outbound action, including on
  reconnection to the internet (see #7); audit log makes exfiltration
  attempts visible even if a single decision is wrong.

### 6. Battery exhaustion attacks
A peer (or group of peers) forces excessive radio activity, connection
churn, or relay work to drain a target device's battery.
- **Mitigation**: rate limiting and backoff on connection attempts and relay
  requests; battery-aware policies that degrade participation (e.g. stop
  relaying) below a battery threshold; per-peer resource budgets.

### 7. Regaining internet connectivity
A device that was mesh-only reconnects to the internet while still holding
mesh-derived data or pending relay obligations.
- **Mitigation**: the Guardrail Engine's default-deny posture applies to
  internet egress too — nothing crosses from mesh to internet without an
  explicit policy allowing it; no silent "sync when online" behavior for
  mesh data.

### 8. Single device compromise within the mesh
One participant's device is compromised (malware, physical access) and used
to attack peers or the mesh itself.
- **Mitigation**: no participant is implicitly trusted beyond its granted
  capabilities; relays cannot read payloads they forward; a compromised
  device can misbehave only within the scope other devices have granted it,
  which the Guardrail Engine continues to enforce locally on every peer.

## Explicitly out of scope (for now)

- Formal verification of the full protocol (tracked as a stretch goal).
- Defense against a compromised OS/kernel on the user's own device.
- Physical radio-layer attacks (jamming, RF fingerprinting hardware).

## Status

This is a skeleton threat model, written before implementation of Phase 1.
It should be revisited and expanded as each phase lands, especially before
any feature that adds a new inbound or outbound action.
