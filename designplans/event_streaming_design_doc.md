# Offline First Event Streaming — Design Plan

---
**Status:** 🟡 Design In Progress

**Target:** buoyient 2.0

**Last Updated:** 2026-05-19

---

## Overview

Buoyient provides offline-first support for request/response network interactions
via `SyncableObjectService`. Long-lived **event streaming** — Server-Sent Events
(SSE) and WebSockets — is not yet supported. This document defines how buoyient
will absorb both, while preserving the offline-first contract consumers already
rely on: **the local database is the single source of truth**, reads are always
served from the DB, and writes performed while offline are queued and replayed
when connectivity returns.

## Goals

1. First-class SSE and WebSocket support shipped as a **separate, optional
   module (`:syncable-events`)**, so apps that don't need streaming pay nothing.
2. Consumers read inbound stream data exclusively from the local DB via flows —
   the open socket is never exposed.
3. Inbound events that arrive while the app is connected are persisted durably,
   in order, before they are visible to the consumer.
4. **Reliable resumption** after disconnect: no missed events under normal
   network failures (bounded by server retention).
5. **Reliable outbound** (WebSocket): events the app emits while offline or
   disconnected are queued locally and delivered **at least once** when the
   socket comes back, with client-supplied idempotency keys for server-side
   dedupe.
6. Sensible platform integration: respect Android background limits (FCM-driven
   catch-up via WorkManager, foreground service only when justified) and iOS
   constraints (BGTaskScheduler, URLSession-based sockets, foreground-bias).

## Non-Goals

- Unifying SSE and WebSocket behind a single public API. Each gets its own base
  class. We share private internals (event log, queue, lifecycle) but not the
  public surface.
- True background-persistent streaming. iOS does not support long-lived WS in
  the background; rather than fake it, we lean on catch-up patterns.
- Exactly-once delivery. At-least-once + idempotency keys is the contract.
- Replacing `SyncableObjectService` for entity-update use cases. If a stream is
  really "live entity mutations", consumers can still keep that data in a
  regular `SyncableObjectService` and use streaming only as a wake/catch-up
  signal; the event log is for genuinely event-shaped payloads.

## High-level architecture

```
                  ┌──────────────────────────────────────────┐
                  │            :syncable-events              │
                  │                                          │
   server ──SSE──▶│  SseStreamService<E>                     │
                  │       │                                  │
                  │       ▼                                  │
                  │  ┌─────────────────────────────────┐     │
                  │  │   StreamingEngine (internal)    │     │
                  │  │   - inbound writer              │     │
                  │  │   - outbound dispatcher (WS)    │     │
                  │  │   - cursor tracker              │     │
                  │  │   - lifecycle policy            │     │
                  │  └─────────────────────────────────┘     │
                  │       ▲              │                   │
   server ◀──WS──▶│  WebSocketStreamService<E,T>             │
                  └───────│──────────────│───────────────────┘
                          │              │
                          ▼              ▼
                  ┌─────────────────────────────────┐
                  │     SQLDelight (shared DB)      │
                  │  stream_events                  │
                  │  stream_cursor                  │
                  │  stream_pending_events  (WS)    │
                  └─────────────────────────────────┘
                          ▲
                          │ flow (DB-backed)
                          │
                  ┌─────────────────────────────────┐
                  │     Consumer code (read only)   │
                  └─────────────────────────────────┘
```

Two public service base classes live in `:syncable-events`. Both write inbound
events through a private `StreamingEngine` into a new pair of tables in the
existing buoyient SQLite database. Consumers subscribe to DB-backed flows; they
never see the socket. The `WebSocketStreamService` additionally exposes an
outbound API that funnels through a new pending-events table.

## Module layout

A **new gradle module** `:syncable-events`:

| Module | Artifact | Platforms | Depends on |
|---|---|---|---|
| `:syncable-events` | `com.elvdev.buoyient:syncable-events` | Android, iOS, JVM | `:core` (for `Service`, `ServiceRequestTag`, `ConnectivityChecker`, `EncryptionProvider`, `HttpClientOverride`, `BuoyientLog`) |

`:syncable-events` is a **sibling** of `:syncable-objects` — both build on
`:core`. Apps that need only streaming (no syncable objects) can take this
module alone; apps that need both pull in both. Neither depends on the other.

Both new service base classes (`SseStreamService<E, T>` and
`WebSocketStreamService<E, T>`) implement the `Service<T>` interface from
`:core`, where `T : ServiceRequestTag`. This matches the existing pattern
where `SyncableObjectService` is also a `Service<T>`, so registration and
discovery APIs in `:core` can treat all service kinds uniformly.

Because `BuoyientDatabase` and `DatabaseProvider` live in `:syncable-objects`
(not `:core`), `:syncable-events` ships its **own SQLDelight database**
(`BuoyientEventsDatabase`) and its own platform `DatabaseProvider` for that
DB. The two databases are independent SQLite files; this keeps the modules
fully decoupled at the storage layer. Initialization, encryption hooks
(`EncryptionProvider`), and test infrastructure are reproduced in
`:syncable-events` rather than shared.

### Package layout (mirrors `:syncable-objects`)

```
com.elvdev.buoyient.events                       // top-level service classes
  SseStreamService        : Service<T>            (T : ServiceRequestTag from :core)
  WebSocketStreamService  : Service<T>            (T : ServiceRequestTag from :core)
  StreamEvent             (inbound event interface)

com.elvdev.buoyient.events.serviceconfigs
  SseConfig
  WebSocketConfig
  StreamLifecyclePolicy
  ReconnectPolicy
  RetentionPolicy
  PoisonEventPolicy

com.elvdev.buoyient.events.datatypes
  InboundEvent<E>
  OutboundMessage
  StreamCursor
  StreamConnectionState   (sealed: Connected / Connecting / Disconnected(reason) / Failed)

com.elvdev.buoyient.events.internal              // not public
  StreamingEngine
  InboundWriter
  OutboundDispatcher
  CursorStore
```

## Inbound event log

A single shared table backs both SSE and WS inbound events.

### Schema (`StreamEvents.sq`)

```sql
CREATE TABLE stream_events (
  event_row_id      INTEGER PRIMARY KEY AUTOINCREMENT,
  service_name      TEXT    NOT NULL,             -- partition key (per service)
  server_event_id   TEXT,                          -- Last-Event-ID / cursor token
  sequence_number   INTEGER NOT NULL,              -- monotonically increasing
                                                   --   per service_name, assigned
                                                   --   at write time
  received_at_ms    INTEGER NOT NULL,
  event_type        TEXT,                          -- optional, server-provided
  data_blob         TEXT    NOT NULL               -- serialized E (kotlinx)
);

CREATE INDEX stream_events_by_service
  ON stream_events(service_name, sequence_number);

CREATE UNIQUE INDEX stream_events_dedupe
  ON stream_events(service_name, server_event_id)
  WHERE server_event_id IS NOT NULL;

CREATE TABLE stream_cursor (
  service_name           TEXT PRIMARY KEY,
  last_server_event_id   TEXT,                     -- what we send on reconnect
  last_processed_seq     INTEGER NOT NULL
);
```

- **Append-only.** Events are never updated in place.
- **Retention.** Default is **never prune** — the consumer owns retention.
  A per-service `RetentionPolicy` config slot lets consumers opt in to
  automatic pruning by age (`MaxAge(duration)`), by count
  (`MaxRows(n)`), or by a custom predicate. A manual
  `pruneEventsBefore(seq)` helper is also exposed for one-off cleanup.
- **Encryption at rest.** If the consumer passes an `EncryptionProvider`
  (the same interface from `:core` used by `SyncableObjectService`),
  `data_blob` is encrypted on write and decrypted on read using the same
  per-service provider pattern. If no provider is supplied, blobs are
  stored as plaintext JSON (matching `SyncableObjectService`'s default).
- **Ordering** is enforced by `sequence_number`, assigned in the order events
  are written to the DB (not the order they arrive on the wire — but those are
  the same as long as we serialize writes per service, which we do).
- **Cursor advance is atomic with the insert** in a single SQLDelight
  transaction. This is what guarantees we never advance past unwritten events.

### Consumer API (read side)

`StreamingService` (the shared base of `SseStreamService` and
`WebSocketStreamService`) exposes:

```kotlin
fun observeEvents(): Flow<List<InboundEvent<E>>>            // live, ordered
fun observeEventsSince(seq: Long): Flow<List<InboundEvent<E>>>
suspend fun getEventsSince(seq: Long, limit: Int): List<InboundEvent<E>>
suspend fun pruneEventsBefore(seq: Long)
val connectionState: StateFlow<StreamConnectionState>
```

All read flows are SQLDelight-backed (live queries) so they update automatically
when the inbound writer commits new events — the same pattern as
`getAllFromLocalStoreAsFlow` in `SyncableObjectService`.

## SSE implementation (`SseStreamService<E, T : ServiceRequestTag>`)

`T` is unused on the read path but kept on the type for symmetry with
`Service<T>` and to allow request-tag-typed catch-up triggers (see
"Lifecycle & platform integration").

### Configuration

```kotlin
class SseConfig<E>(
  val serializer: KSerializer<E>,
  val endpoint: HttpRequest,                       // URL, method=GET, headers
  val parser: SseEventParser<E>,                   // server-format → InboundEvent<E>
  val reconnectPolicy: ReconnectPolicy = ReconnectPolicy.default(),
  val lifecyclePolicy: StreamLifecyclePolicy = StreamLifecyclePolicy.AutoManaged,
  val poisonEventPolicy: PoisonEventPolicy = PoisonEventPolicy.DeadLetter,
  val retentionPolicy: RetentionPolicy = RetentionPolicy.NeverPrune,
)
```

### Connection lifecycle

1. Open a Ktor HTTP request with `Accept: text/event-stream`. The request uses
   `HttpClientOverride.httpClient` so mock mode / tests transparently work.
2. On the first connect for a `service_name`, no `Last-Event-ID` header. On
   every subsequent connect, read `stream_cursor.last_server_event_id` and send
   it as `Last-Event-ID` per the SSE spec.
3. Parse events as they arrive; for each event, in a single DB transaction:
   - `INSERT INTO stream_events ...` with a newly-assigned `sequence_number`
   - `UPDATE stream_cursor SET last_server_event_id = ?, last_processed_seq = ?`
4. Reflect connection state through `connectionState: StateFlow<...>`.

### Disconnect handling

SSE servers send a `retry: <ms>` directive; we honour it, otherwise we use
`ReconnectPolicy` (default: exponential backoff 1s → 30s with full jitter,
capped). Reconnects happen automatically while `StreamLifecyclePolicy` says we
should be running.

### Failure modes

- **Transient network error** → reconnect with backoff.
- **HTTP 401/403** → bubble up via `connectionState = Failed(AuthFailure)`,
  stop reconnecting. Consumer must refresh auth and call `restart()`.
- **HTTP 5xx** → reconnect with backoff.
- **Parser error on a specific event** → handled per the configured
  `PoisonEventPolicy` (see invariant I5 in "Ordering"). Default is
  `DeadLetter`: write a `event_type = 'PARSE_ERROR'` row carrying the raw
  bytes, advance the cursor, and surface the row to consumers via
  `observeEvents()`. `Skip` advances the cursor without persisting; `Halt`
  preserves the old behaviour (block the cursor on the bad event) for
  consumers who would rather stop than risk skipping.

## WebSocket implementation (`WebSocketStreamService<E, T : ServiceRequestTag>`)

### Configuration

```kotlin
class WebSocketConfig<E>(
  val incomingSerializer: KSerializer<E>,
  val endpoint: HttpRequest,                       // ws:// or wss:// URL
  val subProtocols: List<String> = emptyList(),
  val incomingParser: WebSocketIncomingParser<E>,  // frame → InboundEvent<E>
  val outgoingEncoder: WebSocketOutgoingEncoder,   // OutboundMessage → frame
  val resumeMessageBuilder: ResumeMessageBuilder?, // app-level resume frame
  val heartbeat: HeartbeatPolicy = HeartbeatPolicy.PingEvery(30_000),
  val reconnectPolicy: ReconnectPolicy = ReconnectPolicy.default(),
  val lifecyclePolicy: StreamLifecyclePolicy = StreamLifecyclePolicy.AutoManaged,
  val poisonEventPolicy: PoisonEventPolicy = PoisonEventPolicy.DeadLetter,
  val retentionPolicy: RetentionPolicy = RetentionPolicy.NeverPrune,
)
```

### Inbound path

Mirrors SSE: parse frame → assign sequence → write to `stream_events` + update
`stream_cursor` in one transaction. Frames identified as control (ack, pong,
resume-complete) are routed to internal handlers, not to `stream_events`.

### Disconnect handling — the hard part

Three independent disconnect detectors:

1. **Socket-level close** (network drop, server close frame, OS suspend).
   Surfaced by the Ktor / OkHttp / NSURLSession layer directly.
2. **Heartbeat timeout.** `HeartbeatPolicy.PingEvery(periodMs)` schedules
   client pings at `periodMs`. If no pong arrives within `periodMs * 1.5` we
   treat the socket as dead and force-close it. This catches half-open
   connections that the OS hasn't noticed (common on cell→wifi handoffs).
3. **App-level error frame.** Server may send `{ "type": "error", ... }`.
   Parsed by `incomingParser`; if it returns `IncomingFrame.FatalError`, we
   close and surface to `connectionState`.

On any disconnect:

- Mark `connectionState = Disconnected(reason)`.
- If `lifecyclePolicy` says we should be running, schedule a reconnect using
  `reconnectPolicy` (default: exponential backoff with full jitter, starting at
  1s, capped at 60s). Reset on successful reconnect *and* first inbound event.
- **Outbound events keep queueing** (see next section). Nothing in the outbound
  path waits on the socket; the dispatcher just stops draining.
- **In-flight outbound events** — i.e. ones sent on the wire but not yet
  acked — are returned to the head of the queue on disconnect. Because each
  has an idempotency key, the server is responsible for dedupe if it
  actually received the original.

### Reconnect & resumption protocol

1. Open WS handshake with the configured headers and sub-protocols.
2. If `resumeMessageBuilder != null` and we have a cursor, send the resume
   frame as the very first message:

   ```kotlin
   resumeMessageBuilder.build(lastServerEventId, lastProcessedSeq)
   ```

   Typical shape: `{ "type": "resume", "since": "<event-id>" }`.
3. Wait for either inbound events (assumed-resumed) or a `resume_ack` /
   `resume_failed` control frame (parser decides). If resume fails (e.g.
   server retention window exceeded), surface to `connectionState =
   Failed(ResumeUnavailable)` and let the consumer decide what to do (typical
   recovery: clear the cursor and reconnect for "from now").
4. Once `connectionState = Connected`, drain the outbound queue.

### Outbound API

```kotlin
suspend fun send(message: OutboundMessage, requestTag: T): SendReceipt
fun sendAsync(message: OutboundMessage, requestTag: T): SendReceipt
```

- `requestTag: T` is the same `ServiceRequestTag` type used by
  `SyncableObjectService` — the events module does not introduce a parallel
  tag type. Implementations enumerate their tag enum once and use it for
  both kinds of services if they have both.
- `send` enqueues + (if connected) attempts immediate dispatch. The returned
  `SendReceipt` carries the client-assigned `idempotencyKey` and a `Flow<SendState>`
  (`Queued` / `InFlight` / `Acked` / `Failed`).
- `sendAsync` is the fire-and-forget variant.
- The idempotency key is generated client-side (`uuid4()`) on enqueue. It is
  included in every wire-format outbound frame and in every retry.

## Outbound queue (WebSocket only)

### Schema (`StreamPendingEvents.sq`)

```sql
CREATE TABLE stream_pending_events (
  pending_event_id   INTEGER PRIMARY KEY AUTOINCREMENT,
  service_name       TEXT    NOT NULL,
  idempotency_key    TEXT    NOT NULL UNIQUE,
  enqueued_at_ms     INTEGER NOT NULL,
  request_tag        TEXT,                          -- serialized ServiceRequestTag (from :core)
  payload_blob       TEXT    NOT NULL,              -- serialized OutboundMessage
  in_flight          INTEGER NOT NULL DEFAULT 0,    -- 1 while on the wire
  attempt_count      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX stream_pending_events_by_service
  ON stream_pending_events(service_name, pending_event_id);
```

### Lifecycle of an outbound event

1. **Enqueue.** `send()` inserts a row with `in_flight = 0`. Returns a
   `SendReceipt` immediately. UI can show "sending".
2. **Dispatch.** A single global dispatcher loop picks the oldest row across
   *all* services (ordered by `pending_event_id`, which is globally monotonic
   because the table is shared). For each row it sets `in_flight = 1` and
   writes the frame on that service's socket, waiting for the ack before
   advancing — one in-flight message at a time globally. This is what makes
   the **cross-service ordering guarantee** below hold.
   - If the target service's socket is not currently `Connected`, the
     dispatcher pauses on that row until either (a) the service reconnects,
     or (b) the consumer explicitly calls `skipDisconnectedServices()` to
     opt out of strict global ordering for that drain pass. Pausing keeps
     ordering airtight at the cost of head-of-line blocking — see the
     trade-off note at the end of the section.
3. **Ack.** Server replies with a control frame referencing the
   `idempotency_key`. Parser surfaces it as `IncomingFrame.Ack(key)`. The
   dispatcher deletes the matching row and emits `SendState.Acked` on the
   receipt's flow.
4. **Disconnect mid-flight.** Rows with `in_flight = 1` are reset to
   `in_flight = 0` (atomically) at disconnect. On reconnect, they are
   re-sent — server dedupes by `idempotency_key`.
5. **Permanent failure.** If a `Nack(key, reason)` arrives, the row is
   removed and the receipt's flow emits `SendState.Failed(reason)`. (No
   retry — server has explicitly rejected.)

### At-least-once contract

- A row is **only** deleted on `Ack` or `Nack`.
- A row is **never** deleted on disconnect.
- Therefore, every enqueued message is delivered at least once unless the
  consumer explicitly cancels it (a `cancelSend(idempotencyKey)` API is
  exposed but should be used sparingly — once a frame has hit the wire it
  may still be processed by the server).

### Ordering

The global single-dispatcher design + serialized DB writes guarantee:

- **For a single `service_name`:** outbound messages are delivered to the
  server in enqueue order.
- **Across services:** outbound messages are delivered in the order they
  were `send()`-ed at the client, regardless of which service they target.
  This is the contract `:syncable-events` provides.

**Trade-off — head-of-line blocking.** Because there is one global queue and
one in-flight slot, an outbound message targeting a service whose socket is
down will stall newer messages targeted at services whose sockets are up.
This is the price of the cross-service ordering guarantee. Consumers that
don't need strict global ordering can call `skipDisconnectedServices()` on a
per-drain basis, or — more durably — split unrelated traffic across multiple
host apps. (A future `OutboundQueueStrategy.PerServiceFifo` opt-out is
captured in "Future work".)

## Ordering — guarantees, contracts, and invariants

Ordering claims appear in several places above (inbound event log, outbound
queue). This section consolidates them, names the server contracts they
depend on, and enumerates the internal invariants the library must uphold
for the claims to hold.

### What `:syncable-events` guarantees to consumers

| # | Guarantee | Scope |
|---|---|---|
| G1 | Inbound events appear to consumers in the same order the server emitted them. | Per service |
| G2 | Inbound events are not duplicated, even across reconnects and catch-up runs. | Per service |
| G3 | No inbound event is silently skipped — at worst it is dead-lettered and visible to the consumer (see I5). | Per service |
| G4 | Outbound messages reach the server in the order they were `send()`-ed at the client. | Per service AND across services |
| G5 | Every successfully-enqueued outbound message is delivered to the server at least once unless explicitly cancelled. | Per service AND across services |

### Server contracts required for the guarantees to hold

These are requirements on any server that wants to integrate with
`:syncable-events`. If the server cannot meet them, the corresponding
guarantee degrades or breaks.

| # | Contract | Needed for |
|---|---|---|
| S1 | Server-assigned event IDs are **monotonically increasing per service** for the lifetime of a given client cursor. Failover/replica restarts must not roll the ID space backwards. | G1, G2 |
| S2 | The server emits events on the wire in the same order they will be replayed during cursor-based resumption. | G1 |
| S3 | The server tolerates duplicate outbound messages with the same `idempotency_key` (either dedupes server-side or guarantees the operation is idempotent). | G5 without observable side effects |
| S4 | For WebSockets: ack/nack frames carry the original `idempotency_key`, and a frame is acked at most once. | Outbound at-least-once accounting |
| S5 | For SSE: standard `Last-Event-ID` header semantics are honoured (replay strictly after the cursor, or with the cursor event included — either is fine; mixed behaviour within one server is not). | G2 on resumption |

### Internal invariants the library enforces

These are constraints on the implementation. They are not visible to
consumers, but every guarantee above depends on them.

| # | Invariant | Enforces |
|---|---|---|
| I1 | **At most one open connection per service at a time.** A per-service mutex gates `connect()`. Catch-up workers reuse the open connection if one exists; they never open a second. Without this, two writers race on `sequence_number` and produce duplicate writes. | G1, G2 |
| I2 | **Exactly one outbound dispatcher process-wide at a time.** A process-level singleton; foreground takeover replaces the catch-up worker's dispatcher rather than running in parallel. The dispatcher claims rows via an optimistic `UPDATE … WHERE in_flight = 0`, so even a brief overlap can't double-send. | G4, G5 |
| I3 | **Inbound write + cursor advance are one SQLDelight transaction.** Already stated in the inbound section; restated here because every other inbound guarantee depends on it. | G1, G2, G3 |
| I4 | **Inbound dedupe by `(service_name, server_event_id)`.** Enforced by a `UNIQUE` index and `INSERT OR IGNORE` semantics, so a server that re-includes the cursor event (per S5) does not cause a duplicate row. | G2 |
| I5 | **Poison-event policy: advance and dead-letter, not halt.** If the parser cannot decode an event, write a `event_type = 'PARSE_ERROR'` row with the raw bytes and advance the cursor. This row is visible to consumers via `observeEvents()`. The alternative — block the cursor at the bad event — was rejected because one bad event would halt the stream forever on every reconnect. Per-service configurable via `PoisonEventPolicy { Skip, DeadLetter (default), Halt }`. | G3 |
| I6 | **WS parser runs single-threaded per socket** and routes each frame to its handler in arrival order. Acks for in-flight outbound messages are therefore resolved before any subsequent outbound dispatch can begin. | G4 + G5 + "one in-flight at a time" |
| I7 | **Outbound enqueue is atomic.** The `INSERT` into `stream_pending_events` and the return of the `SendReceipt` happen in one transaction so the receipt is never visible before the row is durable, and concurrent `send()` callers get globally-ordered `pending_event_id`s. | G4 |
| I8 | **`pending_event_id` is the sole authority for outbound order.** Never sort outbound work by client wall-clock or by enqueue timestamp. | G4 |
| I9 | **`received_at_ms` is metadata only.** Inbound reads must order by `sequence_number`, never by `received_at_ms`. Client wall-clock can move backwards (NTP, manual time change). | G1 |

### Non-guarantees (explicit)

- **No real-time ordering across consumers on different devices.** Two clients
  observing the same stream may write events to their respective local DBs
  at slightly different `sequence_number`s. Ordering is per-client-DB.
- **No exactly-once delivery, inbound or outbound.** G2 (no inbound dupes)
  holds because of I4, but it is an at-least-once mechanism enforced by
  client-side dedupe, not an exactly-once protocol.
- **No bounded latency.** Head-of-line blocking from a disconnected service
  can delay newer messages indefinitely (this is the cost of G4 across
  services; `skipDisconnectedServices()` is the opt-out).
- **No gap detection on inbound.** If S1 is violated and the server skips
  an event ID, the client cannot tell. v1 does not surface a "missing
  event" signal — adding one is captured in Future work.

## Lifecycle & platform integration

```kotlin
sealed interface StreamLifecyclePolicy {
  object AutoManaged : StreamLifecyclePolicy        // default
  object Manual : StreamLifecyclePolicy             // consumer calls start/stop
  data class Custom(val driver: StreamLifecycleDriver) : StreamLifecyclePolicy
}
```

### `AutoManaged` (default)

- **Foreground → Connected.** When the app enters foreground (Lifecycle
  `RESUMED` on Android, `scenePhase == .active` on iOS), `start()` is called.
- **Background → Disconnected.** When the app backgrounds, `stop()` is called
  after a short grace window (default 30s) — avoids churn for quick app
  switches. Outbound queue is preserved.
- **Periodic catch-up via WorkManager / BGTaskScheduler.** Even when streaming
  is closed, the existing buoyient sync infrastructure runs periodically. We
  hook in a `StreamCatchUpWorker` that opens the WS briefly (or replays SSE),
  drains anything pending, then closes. The cursor + event log mean this is
  safe and lossless.

### Android-specific best practices (documented in the doc, not auto-magic)

- **FCM-triggered catch-up.** When the server pushes an FCM "you have
  unread events" payload, the app should call
  `Buoyient.events().catchUp(serviceName)`. This enqueues a one-time
  `StreamCatchUpWorker`. We won't auto-register an FCM receiver — the host
  app owns FCM — but we will document the integration pattern.
- **Foreground service guidance.** A consumer *may* run a foreground service
  to keep the WS alive in the background (e.g. live driver-tracking apps).
  The doc enumerates when this is appropriate (active user task, ongoing
  navigation/call) and when it isn't (general chat — use FCM instead).
  We do **not** ship a foreground service.

### iOS-specific best practices

- **No background sockets.** iOS will kill the WS within seconds of
  backgrounding. We document this and route everything through
  `BGTaskScheduler` for catch-up.
- **APNS-triggered catch-up** (parallel to Android's FCM path). When the
  server pushes a **silent APNS notification** (`content-available: 1`,
  with `apns-push-type: background` and `apns-priority: 5`), iOS wakes
  the app for ~30 seconds in
  `application(_:didReceiveRemoteNotification:fetchCompletionHandler:)`.
  In that handler the host app should call
  `Buoyient.events().catchUp(serviceName)`, which:
  1. Opens the WS (or replays SSE) using the persisted cursor.
  2. Drains any inbound events into `stream_events`.
  3. Flushes the outbound queue (subject to the ordering rules above).
  4. Closes the socket.
  5. Calls the system completion handler.
  As with FCM on Android, we don't auto-register a push receiver —
  the host app owns its push token and APNS plumbing — but we will ship
  a documented integration recipe.
- **For higher-urgency real-time:** push types `apns-push-type: alert`
  (user-visible) or PushKit/VoIP can wake the app more reliably than
  silent push, but they come with App Store policy constraints. PushKit
  is out of scope for buoyient core; documented as an escape hatch for
  true real-time apps (calls, live navigation).
- **`URLSessionWebSocketTask`** is the iOS WS implementation. Plug into
  `HttpClientOverride` for tests/mock mode just like the existing services.

### Connectivity integration

The streaming engine subscribes to the existing `ConnectivityChecker` and:

- Suspends reconnect attempts while offline (no point burning battery
  retrying a TCP connect that can't succeed).
- Triggers an immediate reconnect when connectivity is restored.
- Triggers `Buoyient.syncNow()`-style drain of any other pending REST
  requests on the same reconnect tick.

## Public API surface

### Registration

```kotlin
// in app setup
Buoyient.registerEventServices(
  myChatService,
  myNotificationsService,
)
```

The registry tracks streaming services in parallel to `SyncableObjectService`s
so periodic catch-up workers know what to drain.

### Read-only consumer API (both SSE and WS)

```kotlin
val service = myChatService
service.observeEvents()                             // Flow<List<InboundEvent<E>>>
service.observeEventsSince(seq)
service.connectionState                             // StateFlow
service.pruneEventsBefore(seq)
service.restart()                                   // for Manual lifecycle
service.stop()
```

### Outbound consumer API (WS only)

```kotlin
val receipt = service.send(OutboundMessage(...), MyRequestTag.ChatMessage)
receipt.idempotencyKey
receipt.state                                       // Flow<SendState>
service.cancelSend(receipt.idempotencyKey)          // best-effort
```

## Testing

A new `:syncable-events-testing` module (parallel to `:testing`) exposes:

- `TestStreamEnvironment` — in-memory DB, fake `ConnectivityChecker`, fake
  socket pair (one side acts as the server).
- `FakeSseServer` / `FakeWebSocketServer` — scriptable: push events, force
  disconnect, simulate ack delays, simulate `resume_failed`.
- Assertion helpers: `assertCursorIs(seq)`, `assertOutboundQueueSize(n)`,
  `assertInFlight(key)`, `awaitConnected()`.

Required test coverage at design-doc-checkpoint level:

1. SSE happy path: events arrive in DB, cursor advances.
2. SSE reconnect: kill connection, verify `Last-Event-ID` is sent, no dupes.
3. WS reconnect with successful resume.
4. WS reconnect with `resume_failed` → `Failed(ResumeUnavailable)`.
5. Outbound while offline: events stay in queue, drain on reconnect.
6. Outbound in-flight at disconnect: re-sent on reconnect with same
   idempotency key.
7. At-least-once: server-side dedupe verified via fake server.
8. Heartbeat timeout: half-open connection detected and recycled.
9. FIFO ordering preserved across reconnects.

## Resolved design decisions

These were open during initial drafting and are now settled. They live here
as a record so reviewers can see the trade-off:

- **Encryption at rest:** honour `EncryptionProvider` from `:core` if the
  consumer configures one; otherwise plaintext JSON, matching
  `SyncableObjectService`. ([see "Inbound event log"](#inbound-event-log))
- **Retention policy:** default is never prune; consumer opts into
  `RetentionPolicy.MaxAge`, `MaxRows`, or a custom predicate per service.
  ([see "Inbound event log"](#inbound-event-log))
- **Cross-service outbound ordering:** guaranteed via a single global FIFO
  dispatcher with one in-flight message at a time. Head-of-line blocking
  is the accepted trade-off; `skipDisconnectedServices()` is an opt-out per
  drain. ([see "Outbound queue"](#outbound-queue-websocket-only))

## Open questions

1. **Shared stream services.** Can two services share a single WS
   connection (multiplexing)? Out of scope for v1; documented as future
   work.
2. **WorkManager / BGTaskScheduler integration plumbing.** Specifically,
   does the existing `SyncRunner` get a new step, or do we register a
   separate worker? Decision deferred to implementation; both are viable.
3. **Wire-format conventions.** We don't dictate a wire format for resume
   / ack / nack frames — the parser/encoder hooks let the app pick. Should
   we ship a "default" pair (e.g. JSON envelope with `type` field) as a
   convenience? Recommended for v1.

## Future work (explicitly out of scope for v1)

- gRPC streaming (bidi server streams) — would be a third sibling service
  in `:syncable-events`.
- Multiplexed connections (one socket, many services).
- Built-in FCM receiver for catch-up triggering (Android).
- Built-in APNS silent-push receiver for catch-up triggering (iOS).
- Foreground service helper for Android.
- `OutboundQueueStrategy.PerServiceFifo` opt-out for consumers willing to
  give up cross-service ordering to avoid head-of-line blocking.
- Inbound gap detection — surface a "missing event ID" signal to consumers
  when server-assigned event IDs skip (server contract S1 violated). v1
  silently trusts the server.
