# Setting Up buoyient in Your iOS App

This guide walks through adding buoyient to a Swift/SwiftUI iOS app. After completing these steps, your app will have offline-first sync infrastructure ready for you to build services on top of.

**Prerequisites:** An iOS app targeting iOS 15+ with Xcode 15+ and Swift 5.9+.

---

## Step 1: Add the Dependency

### Option A: Swift Package Manager (recommended)

1. Build the XCFramework from the buoyient repo:

   ```bash
   ./gradlew :syncable-objects:assembleBuoyientReleaseXCFramework
   ```

   This produces `syncable-objects/build/XCFrameworks/release/Buoyient.xcframework`.

2. In Xcode, go to **File > Add Package Dependencies**.

3. Add the buoyient repository URL or reference the local package:
   - **Local:** point to the repo root which contains `Package.swift`
   - **Remote:** use the GitHub release URL (when published)

4. Select the `Buoyient` library target and add it to your app target.

### Option B: Manual framework

1. Build the XCFramework as above.
2. Drag `Buoyient.xcframework` into your Xcode project.
3. In your target's **General > Frameworks, Libraries, and Embedded Content**, ensure `Buoyient.xcframework` is listed with **Do Not Embed** (it's a static framework).

---

## Step 2: Initialize buoyient

Unlike Android (which uses `androidx.startup` for automatic initialization), iOS requires manual setup at app launch.

### SwiftUI App lifecycle

```swift
import SwiftUI
import Buoyient

@main
struct MyApp: App {
    init() {
        // Register the background sync handler for BGTaskScheduler
        IosSyncScheduleNotifier.Companion.shared.registerHandler()

        // Configure global auth headers (evaluated on every request)
        Buoyient.shared.globalHeaderProvider = GlobalHeaderProvider {
            [("Authorization", "Bearer \(AuthManager.shared.currentToken)")]
        }

        // Register services for background sync
        Buoyient.shared.registerServices(services: [
            todoService.syncDriver,
            noteService.syncDriver,
        ])
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

### UIKit App lifecycle

```swift
import UIKit
import Buoyient

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        IosSyncScheduleNotifier.Companion.shared.registerHandler()

        Buoyient.shared.globalHeaderProvider = GlobalHeaderProvider {
            [("Authorization", "Bearer \(AuthManager.shared.currentToken)")]
        }

        Buoyient.shared.registerServices(services: [
            todoService.syncDriver,
            noteService.syncDriver,
        ])

        return true
    }
}
```

### Info.plist configuration

Add the background task identifier to your `Info.plist` so iOS allows background sync via BGTaskScheduler:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>com.elvdev.buoyient.sync</string>
</array>
```

Also enable the **Background Modes** capability in your target settings and check **Background fetch** and **Background processing**.

---

## Step 3: Register Services

Register your services at app launch as shown in Step 2. The `registerServices()` call accepts the `syncDriver` property from each service instance.

```swift
let todoService = TodoService()
let noteService = NoteService()

Buoyient.shared.registerServices(services: [
    todoService.syncDriver,
    noteService.syncDriver,
])
```

> **Note:** Unlike Android's Hilt multibinding, iOS does not have a DI framework equivalent built in. You can use any Swift DI approach (manual injection, Swinject, etc.) to manage service lifetimes.

---

## Step 3b: Configure Global Auth Headers (optional)

If all your services share the same auth headers, set a `GlobalHeaderProvider` once at startup:

```swift
Buoyient.shared.globalHeaderProvider = GlobalHeaderProvider {
    [("Authorization", "Bearer \(AuthManager.shared.currentToken)")]
}
```

The provider is evaluated at request time, so refreshed tokens are picked up automatically.

Header application order (same as Android):

1. **Global headers** from `Buoyient.shared.globalHeaderProvider`
2. **Service headers** from `ServerProcessingConfig.serviceHeaders`
3. **Request headers** from `HttpRequest.additionalHeaders`

---

## Step 4: Trigger Sync on Foreground Transitions

iOS background sync via BGTaskScheduler is throttled by the OS (roughly once per hour, and may not run at all). For reliable sync, trigger sync-up when your app enters the foreground:

### SwiftUI

```swift
@main
struct MyApp: App {
    @Environment(\.scenePhase) var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onChange(of: scenePhase) { newPhase in
                    if newPhase == .active {
                        Buoyient.shared.syncNow { success in
                            print("Sync completed: \(success)")
                        }
                    }
                }
        }
    }
}
```

### UIKit

```swift
func applicationDidBecomeActive(_ application: UIApplication) {
    Buoyient.shared.syncNow { success in
        print("Sync completed: \(success)")
    }
}
```

---

## Step 5: Verify the Setup

1. **Build the app** — confirm no framework linking errors
2. **Launch the app** — verify no crashes during initialization
3. **Create an item** — call your service's create method and verify it returns a response
4. **Check local persistence** — call `service.getAllFromLocalStore()` and confirm the item is stored
5. **Trigger sync** — call `Buoyient.shared.syncNow()` and verify items sync to the server

---

## Swift API Ergonomics (SKIE)

buoyient uses [SKIE](https://skie.touchlab.co/) to provide Swift-native API ergonomics:

- **`suspend` functions** are available as Swift `async/await` — no completion handler callbacks needed
- **`sealed class`/`sealed interface`** types map to Swift `enum` — use `switch` for exhaustive matching
- **`Flow`/`StateFlow`** bridges to Swift `AsyncSequence` — use `for await` loops

### Example: Creating an item with async/await

```swift
func createTodo(title: String) async throws {
    let result = try await todoService.addTodo(title: title)

    switch result {
    case let finished as SyncableObjectServiceResponse.FinishedNetworkResponseReceived:
        print("Created on server: \(finished.data)")
    case is SyncableObjectServiceResponse.FinishedStoredLocally:
        print("Stored locally, will sync later")
    default:
        print("Unexpected response")
    }
}
```

### Example: Observing local store changes

```swift
func observeTodos() async {
    for await todos in todoService.getAllFromLocalStoreAsFlow() {
        // Update your @Published property or SwiftUI @State
        self.todos = todos
    }
}
```

---

## What's Next

With the library set up, you're ready to build services:

- **Create a service** — see [`docs/creating-a-service.md`](creating-a-service.md) for the complete walkthrough (data models, request tags, server configs, and service classes are defined in Kotlin and shared across platforms)
- **Write tests** — see [`docs/integration-testing.md`](integration-testing.md) for automated tests using `TestServiceEnvironment`
- **Set up mock mode** — see [`docs/mock-mode.md`](mock-mode.md) for running the app against fake responses

---

## iOS-Specific Considerations

### Background Sync Limitations

iOS background sync is "best effort" — BGTaskScheduler is throttled by the OS and may not run predictably. Key differences from Android:

| Aspect | Android | iOS |
|--------|---------|-----|
| Background engine | WorkManager (guaranteed delivery) | BGTaskScheduler (best effort) |
| Network constraint | `NetworkType.CONNECTED` built-in | Must check manually |
| Retry on kill | Automatic (WorkManager persists) | Queue persists in SQLite; syncs on next foreground |
| Recommended approach | Automatic via WorkManager | Call `syncNow()` on foreground transitions |

### Memory and Threading

- buoyient uses Kotlin coroutines internally. On iOS, these run on the Kotlin/Native runtime.
- All service methods that are `suspend` functions are exposed as Swift `async` functions via SKIE.
- The local SQLite database is stored at `buoyient.db` in the app's documents directory.

### Connectivity Detection

buoyient uses `NWPathMonitor` (Network.framework) on iOS for connectivity detection. This is handled automatically — no additional configuration needed.

---

## Troubleshooting

### Framework not found

Ensure the XCFramework is properly linked in your target's **Frameworks, Libraries, and Embedded Content** section. For static frameworks, use **Do Not Embed**.

### BGTaskScheduler not firing

- Verify the task identifier `com.elvdev.buoyient.sync` is in your Info.plist under `BGTaskSchedulerPermittedIdentifiers`
- Enable Background Modes capability with **Background fetch** and **Background processing** checked
- Note: BGTaskScheduler does not fire in the Simulator reliably — test on a real device

### Items not syncing

- Ensure `syncNow()` is called on foreground transitions (see Step 4)
- Verify network connectivity
- Check that services are registered before any sync attempts
