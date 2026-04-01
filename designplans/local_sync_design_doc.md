# Local Sync — Design Plan

---
**Status:** 🟡 Design In Progress

**Target:** buoyient 2.0

**Last Updated:** 2026-03-31

---

## Overview

Local Sync enables multiple co-located Android devices to synchronize data through a local "beacon" device using Nearby Connections, rather than each device independently communicating with the remote server. This reduces redundant network traffic, ensures consistent ordering of operations across devices, and enables offline-capable multi-device workflows.

**V1 Scope:** Android only, using [Google Nearby Connections API](https://developers.google.com/nearby/connections/overview) (P2P, no internet required).

---

## Core Concepts

### Net
A group of local devices discovered via Nearby Connections that are collaborating on the same data. A net forms dynamically — devices join and leave as they come in and out of range.

### Beacon
A single device in the net designated as the coordination point. The beacon runs a lightweight "local server" and is the only device that accumulates `PendingSyncRequest` entries in its database. All other devices in the net route their write operations through the beacon.

### Observer
Any non-beacon device in the net. Observers send their write operations to the beacon and receive data updates pushed from it. Observers **never** store `PendingSyncRequest` entries locally while connected to a net — this is the key invariant that ensures uniform request ordering.

---

## Beacon Election

### Initial Election
The first device that advertises a net and finds no existing beacon claims the beacon role.

### Preferred Beacons
Consumers can mark certain devices as **preferred beacons** (e.g., a stationary tablet or POS terminal). If a preferred beacon joins a net whose current beacon is *not* preferred, the preferred device takes over the beacon role.

### Beacon Handoff Protocol

When a beacon handoff occurs (preferred beacon takes over, or current beacon leaves):

1. The outgoing beacon **transfers its pending request queue** to the incoming beacon.
   - All `PendingSyncRequest` entries are serialized and sent over the Nearby Connections channel.
   - The incoming beacon inserts them into its own local DB, preserving their original ordering (by `queued_at` timestamp or a sequence number).
2. The outgoing beacon **confirms transfer** and clears its local queue only after receiving acknowledgment.
3. The incoming beacon announces itself to all observers.
4. Observers redirect all future requests to the new beacon.

> **Open Question — Resolved:** Pending requests on the previous beacon are transferred to the new beacon during handoff. This ensures no queued operations are lost and the new beacon can continue syncing them to the server in the correct order.

### Beacon Departure Without Handoff (Crash / Disconnect)

If the beacon leaves unexpectedly (crash, out of range):

1. Remaining devices detect disconnection via Nearby Connections callbacks.
2. A new beacon is elected from the remaining devices (preferred beacons first, then by a deterministic tiebreaker such as earliest join time).
3. The new beacon begins accepting requests immediately.
4. **The departed device's pending requests are orphaned** — they will sync to the server when that device regains server connectivity on its own. This may produce conflicts resolved by the existing `SyncableObjectRebaseHandler` 3-way merge.

> **Design Decision Needed:** Should observers cache a copy of requests they sent to the beacon (marked as "delegated") so they can re-queue them if the beacon crashes before syncing? This adds complexity but improves durability.

---

## Request Routing

### Decision Matrix

| Condition | Route |
|-----------|-------|
| Device is the beacon | Process locally (existing `SyncableObjectService` behavior) |
| Device is an observer, `processingConstraints = OnlineOnly` | Contact the server directly (bypass beacon) |
| Device is an observer, any other constraint | Route through the beacon |

### Key Invariant
A non-beacon device **never** stores `PendingSyncRequest` entries in its local database while connected to a net. All pending requests are held exclusively on the beacon. This ensures a single source of truth for request ordering across the net.

### What Happens When an Observer Goes Offline (Leaves the Net)?

When an observer loses connection to the beacon and cannot reach the server:
- It reverts to standard buoyient offline behavior — requests are queued locally as `PendingSyncRequest` entries.
- When it reconnects to a net or regains server connectivity, those requests sync normally.

---

## Beacon Local Server API

The beacon exposes a local API over the Nearby Connections data channel. This is not an HTTP server — it's a message-based protocol over Nearby Connections payloads.

### `send(pendingSyncRequest: PendingSyncRequest): SyncableObject<O>`

Accepts a write operation from an observer.

**Beacon behavior:**
1. Stores the `PendingSyncRequest` in its local DB queue (ensuring uniform ordering across all devices in the net).
2. Applies the change to its local data store.
3. Returns the updated `SyncableObject<O>` to the calling observer with `syncStatus = LOCAL_SYNCED`.
4. Pushes the updated object to all other observers in the net.

**Observer behavior:**
1. Receives the returned object and upserts it into its local data store.
2. Does **not** create a local `PendingSyncRequest`.

### `get(serviceName: String, clientId: String): SyncableObject<O>`

Allows an observer to request the latest version of a specific item from the beacon.

**Usage:** When an observer needs the freshest state (e.g., before displaying an edit form). Observers should prioritize the beacon's version of an item over their own local store while connected to a net.

### `fetchAll(serviceName: String): List<SyncableObject<O>>`

Allows an observer to fetch the current state of all known items for a given service from the beacon. Used for initial sync when joining a net or for periodic reconciliation.

### `lock(serviceName: String, clientId: String, requestingDeviceId: String): LockResult`

Requests an exclusive edit lock on a specific item.

**Beacon behavior:**
1. If the item is not locked → grants the lock, records `(clientId, deviceId, timestamp)`.
2. If the item is already locked by another device → returns `LockResult.Denied(heldBy: String)`.
3. If the item is already locked by the requesting device → returns `LockResult.AlreadyHeld`.

**Effect of a lock:** The beacon will **reject** `send()` requests for a locked item from any device other than the lock holder, returning an error response.

### `unlock(serviceName: String, clientId: String, requestingDeviceId: String): UnlockResult`

Releases a previously acquired lock.

**Auto-release:** Locks are automatically released when the holding device disconnects from the net.

**Lock timeout:** Locks expire after a configurable duration (default: TBD) to prevent deadlocks from unresponsive devices.

---

## New SyncStatus: `LOCAL_SYNCED`

A new `SyncStatus` value is needed:

| Status | Meaning |
|--------|---------|
| `LocalSynced` | The item has been accepted by the beacon but has not yet been confirmed by the remote server. |

`LocalSynced` sits between `PendingCreate`/`PendingUpdate` and `Synced` in the lifecycle. It means:
- The operation is durably stored on the beacon's queue.
- The beacon will sync it to the server on behalf of the originating device.
- The observer does not need to take further action.

---

## Integration with Existing Architecture

### ConnectivityChecker

A new `LocalNetConnectivityChecker` (or extension of the existing interface) is needed:

```kotlin
interface LocalNetConnectivityChecker {
    fun isInLocalNet(): Boolean
    fun isBeacon(): Boolean
    fun getBeaconConnection(): BeaconConnection?
}
```

The existing `ConnectivityChecker.isOnline()` continues to govern server reachability. The local net checker determines whether to route through the beacon.

### SyncableObjectService Changes

The dual-path (online/offline) decision in `create()`, `update()`, and `void()` gains a third path:

```
if (localNetChecker.isInLocalNet() && !localNetChecker.isBeacon()) {
    // Observer path: route through beacon
    if (processingConstraints is OnlineOnly) {
        // Bypass beacon, contact server directly
    } else {
        // Send to beacon via local server API
    }
} else {
    // Existing behavior (beacon or standalone device)
}
```

### SyncDriver

The beacon device's `SyncDriver` operates normally — it owns the pending request queue and syncs to the server. No changes needed to `SyncDriver` for V1.

Observer devices' `SyncDriver` should **pause sync-up** while connected to a net (since they have no pending requests). Sync-down can optionally be paused too if the beacon is pushing updates.

### ProcessingConstraints

No new constraint values needed. The existing `OnlineOnly` constraint is reused to mean "bypass the beacon and talk to the server directly."

---

## Nearby Connections Integration

### Discovery & Connection

- **Strategy:** `Strategy.P2P_STAR` — one-to-many topology matching the beacon/observer model. The beacon is the hub; observers connect only to the beacon, never to each other.
- **Service ID:** A consumer-configurable string identifying the app/use case (e.g., `"com.myapp.buoyient.localsync"`).
- **Advertising:** The beacon advertises. Observers discover and connect.
- **Payload type:** `Payload.Type.BYTES` for request/response messages (serialized via `kotlinx.serialization`).

### Message Protocol

Messages are serialized Kotlin objects sent as byte payloads:

```kotlin
@Serializable
sealed class LocalSyncMessage {
    @Serializable data class SendRequest(val pendingSyncRequest: SerializedPendingSyncRequest) : LocalSyncMessage()
    @Serializable data class SendResponse(val syncableObject: String, val syncStatus: String) : LocalSyncMessage()
    @Serializable data class GetRequest(val serviceName: String, val clientId: String) : LocalSyncMessage()
    @Serializable data class GetResponse(val syncableObject: String?) : LocalSyncMessage()
    @Serializable data class FetchAllRequest(val serviceName: String) : LocalSyncMessage()
    @Serializable data class FetchAllResponse(val objects: List<String>) : LocalSyncMessage()
    @Serializable data class LockRequest(val serviceName: String, val clientId: String, val deviceId: String) : LocalSyncMessage()
    @Serializable data class LockResponse(val result: LockResult) : LocalSyncMessage()
    @Serializable data class UnlockRequest(val serviceName: String, val clientId: String, val deviceId: String) : LocalSyncMessage()
    @Serializable data class UnlockResponse(val result: UnlockResult) : LocalSyncMessage()
    @Serializable data class BeaconPush(val serviceName: String, val syncableObject: String) : LocalSyncMessage()
    @Serializable data class BeaconHandoff(val pendingRequests: List<SerializedPendingSyncRequest>) : LocalSyncMessage()
    @Serializable data class BeaconHandoffAck(val success: Boolean) : LocalSyncMessage()
}
```

---

## Item Locking

### Purpose
Prevents concurrent edits to the same item from different devices in the net. Without locking, two observers could submit conflicting updates for the same object, and the beacon would queue them in arrival order — the second would overwrite the first.

### Behavior
- Locks are **opt-in** — consumers choose when to acquire locks (e.g., when opening an edit screen).
- Locks are **per-item** (identified by `serviceName + clientId`).
- The beacon maintains an in-memory lock table: `Map<Pair<ServiceName, ClientId>, LockEntry>`.
- A locked item can still be read by any device — locks only block writes.

### Lock Lifecycle
1. Observer requests lock via `lock()`.
2. Beacon grants or denies.
3. Observer performs edits, sending updates via `send()`.
4. Observer releases lock via `unlock()`.
5. If observer disconnects, beacon auto-releases all its locks.

---

## Open Design Questions

1. **Observer-side durability:** Should observers keep a "shadow copy" of requests they've sent to the beacon, so they can re-queue if the beacon crashes before syncing to the server? Tradeoff: more durable but adds complexity and potential for duplicate requests.

2. **Lock timeout duration:** What's a reasonable default? Options: 30s, 60s, 5min, no timeout (rely on disconnect auto-release only).

3. **Beacon push granularity:** Should the beacon push every individual update to all observers, or batch updates periodically? Real-time push is simpler but could be chatty on large nets.

4. **Net partitioning:** If the net splits (e.g., two groups of devices move apart), each partition would elect its own beacon and diverge. When they rejoin, how should the data be reconciled? The existing 3-way merge/conflict resolution should handle this, but it may need testing.

5. **Multi-service coordination:** The beacon processes requests across all registered services. Should the local server API be service-aware from the start, or should V1 assume a single service per net?

6. **Encryption:** Should local net traffic be encrypted beyond what Nearby Connections provides? Nearby Connections uses encrypted channels by default, but should buoyient add an additional layer (using the existing `EncryptionProvider` interface)?

---

## V1 Scope Summary

| In Scope | Out of Scope |
|----------|-------------|
| Android only | iOS support |
| Nearby Connections transport | WiFi Direct, Bluetooth LE, custom transports |
| Beacon election (first-come + preferred) | Weighted election algorithms |
| Beacon handoff (voluntary) | Automatic load balancing |
| Request routing through beacon | Mesh routing (multi-hop) |
| Item locking (opt-in) | Automatic conflict prevention |
| `LOCAL_SYNCED` status | Partial sync / selective replication |
| Push updates to observers | Differential sync / CRDTs |
