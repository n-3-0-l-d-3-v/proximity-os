# ADR 0003 — Shared list replication

Status: accepted
Date: 2026-08-28

## Context

The MVP calls for a shared list (shopping/todo) that works fully offline.
On a proximity mesh that means:

- Two people edit the same list while out of range of each other.
- They reconnect in either direction, possibly via different peers.
- Messages arrive out of order, get duplicated by retries, or never arrive.
- There is no server to arbitrate, and no clock anyone can trust.

"Last write wins on the whole list" would silently discard one person's
work — the exact failure that makes shared lists infuriating.

## Decision

Replicate lists as an **operation-based CRDT** with last-writer-wins
registers **per field**, and support **state merge** for reconnection.

### Per-field, not per-item

Each mutable field (`text`, `done`, `removed`) carries its own version
stamp. If you tick an item off while I rename it, we end up with the
renamed item ticked off. With a single per-item stamp one of those edits
would vanish, and the person who lost it would have no way to know.

### Lamport counters, not wall-clock time

Version stamps order by a Lamport counter, tie-broken by device ID.

Wall-clock time was rejected because two phones that have been apart may
disagree wildly about what time it is. A device with a badly wrong clock
would win *every* conflict forever, silently overwriting everyone else —
and the symptom (edits vanishing) points nowhere near the cause (a clock).
A Lamport counter only moves forward, and only in response to observed
events, so no device can dominate by being wrong about the time.

Wall-clock time is still stored, but only for display and ordering.

### Deletion is a tombstone

A removed item stays in the map with a `removed` flag. A peer that never
heard about the delete still holds the item, and would resurrect it on the
next sync if deletion were an actual removal.

### Both operations and state merge

Operations are broadcast for live editing — small and immediate. Full
replicas are exchanged when a session is established, because replaying
every operation ever made does not scale and a peer may have missed an
unbounded number of them.

Both paths converge to the same state; `merge` is commutative, associative
and idempotent over the same field-wise rules.

## An invariant worth stating explicitly

**A stamp may only advance together with the value it stamps.**

The first implementation had `ADD` advance `doneStamp` without writing a
`done` value — reasoning that an add "touches" the item. This made the
outcome depend on arrival order: an earlier `SET_DONE` was suppressed if it
happened to arrive after the `ADD`, and not suppressed otherwise. The
randomised convergence test caught it; no hand-written example had.

A companion rule covers fields with no stamp at all: `createdAtEpochMillis`
converges by taking the **minimum** across every operation touching the
item. Minimum is commutative, associative and idempotent, so replicas
agree. Before that fix, creation time came from whichever operation first
materialised the item — so two phones sorted the same list differently.

## Testing

Convergence is tested as a property, not as examples: operations are
shuffled, duplicated, and reversed across simulated replicas, which must
end byte-identical. Both bugs above were found this way, and neither would
have been caught by testing the obvious sequences.

## Consequences

- Tombstones accumulate. Acceptable at shopping-list scale; a garbage
  collection scheme would need agreement that every replica has seen a
  delete, which is a much harder problem and not worth solving yet.
- Every item carries three stamps, so state is larger than the data. At
  these sizes that is irrelevant.
- Concurrent edits to the *same* field still lose one side. That is
  unavoidable without merging text semantically, and is the right trade for
  list items.
- Deleting a list is deliberately **local only**. Removing your own copy
  does not tell peers to drop theirs; deleting someone else's data should
  be an explicit act, not a side effect of tidying your own phone.
