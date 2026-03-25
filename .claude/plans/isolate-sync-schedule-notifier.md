# Isolate SyncScheduleNotifier to LocalStoreManager

## Current State

`SyncScheduleNotifier` is used in **three classes**:

1. **`LocalStoreManager`** — calls `scheduleSyncIfNeeded()` after `insertLocalData()`, `updateLocalData()`, and `voidData()` (lines 117, 181, 256)
2. **`SyncDriver`** — calls `scheduleSyncIfNeeded()` in `init {}` when `autoStart=true` (line 68)
3. **`SyncableObjectService`** — calls `scheduleSyncIfNeeded()` after `resolveConflict()` succeeds (lines 718, 732)

All three classes receive the notifier independently via constructor, creating redundant coupling.

## Analysis of the Three Trigger Reasons

### 1. New pending request added / request newly eligible (after conflict resolution)
- **`LocalStoreManager` calls (insert/update/void)** — already handled, stays as-is
- **`SyncableObjectService.resolveConflict()`** — this is the "newly eligible after conflict resolution" case. The notifier call lives in `SyncableObjectService` (lines 718, 732) but the actual state change happens inside `LocalStoreManager.resolveConflictData()`. We should move the notification into `LocalStoreManager.resolveConflictData()` so it's co-located with the state mutation.

### 2. App restart
- **`SyncDriver.init{}`** calls `scheduleSyncIfNeeded()` to ensure WorkManager has a job enqueued in case the previous one completed/expired. However, on Android, WorkManager already handles this: `DataBuoyInitializer` registers the service provider at app start, and if there are pending requests left from a prior session, `SyncScheduler` can be triggered. But actually — nothing currently triggers WorkManager on cold start *unless* `SyncDriver.init{}` does it. However, `SyncDriver.init{}` is called when each service is constructed (during app startup), so this is really just an "on service init, make sure background sync is scheduled" concern.
- **Recommendation**: Move this to `LocalStoreManager` — add a method like `scheduleIfPendingRequestsExist()` that checks the DB for pending requests and only notifies if there are any. `SyncDriver` calls this on init instead of blindly calling `scheduleSyncIfNeeded()`.

### 3. Device comes back online
- WorkManager's `NetworkType.CONNECTED` constraint already handles this for free. When the device reconnects, WorkManager runs the enqueued `SyncWorker` automatically — **no code change needed**, as long as a work request was enqueued while offline (which happens via trigger #1).

## Plan

### Step 1: Add `scheduleIfPendingRequestsExist()` to `LocalStoreManager`

Add a new method that checks the pending request table and only calls `scheduleSyncIfNeeded()` if there are rows to upload. This replaces the blind `scheduleSyncIfNeeded()` call in `SyncDriver.init{}`.

```kotlin
// LocalStoreManager
fun scheduleIfPendingRequestsExist() {
    if (pendingRequestQueueManager.hasAnyPendingRequests()) {
        syncScheduleNotifier.scheduleSyncIfNeeded()
    }
}
```

This requires adding `hasAnyPendingRequests()` (no clientId filter) to `PendingRequestQueueManager` if it doesn't already exist.

### Step 2: Move conflict-resolution notification into `LocalStoreManager.resolveConflictData()`

Call `syncScheduleNotifier.scheduleSyncIfNeeded()` inside `resolveConflictData()` when the result is `ResolveConflictResult.Resolved` (the conflict was resolved and the pending request is now eligible for upload again). Same for `repairOrphanedConflictStatus()` when it returns `Resolved`.

### Step 3: Remove `SyncScheduleNotifier` from `SyncDriver`

- Remove the `syncScheduleNotifier` constructor parameter from `SyncDriver`
- Replace `syncScheduleNotifier.scheduleSyncIfNeeded()` in `init{}` with `localStoreManager.scheduleIfPendingRequestsExist()`

### Step 4: Remove `SyncScheduleNotifier` from `SyncableObjectService`

- Remove the `syncScheduleNotifier` constructor parameter from `SyncableObjectService`
- Remove the two `syncScheduleNotifier.scheduleSyncIfNeeded()` calls in `resolveConflict()` (now handled by `LocalStoreManager`)
- Stop passing `syncScheduleNotifier` to `SyncDriver` constructor

### Step 5: Update tests

Update any tests that construct `SyncDriver` or `SyncableObjectService` with a `syncScheduleNotifier` parameter.

## Files Changed

| File | Change |
|------|--------|
| `LocalStoreManager.kt` | Add `scheduleIfPendingRequestsExist()`, add notifier call in `resolveConflictData()` and `repairOrphanedConflictStatus()` |
| `PendingRequestQueueManager.kt` | Add `hasAnyPendingRequests()` if not present |
| `SyncDriver.kt` | Remove `syncScheduleNotifier` param, call `localStoreManager.scheduleIfPendingRequestsExist()` in init |
| `SyncableObjectService.kt` | Remove `syncScheduleNotifier` param, remove notifier calls from `resolveConflict()`, stop passing to `SyncDriver` |
| Test files | Update constructor calls |
