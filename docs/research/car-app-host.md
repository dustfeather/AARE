# Hosting third-party car apps (AndroidX Car App Library), media apps and messaging

Research notes for implementing an Android Auto-compatible **host** on the phone.
Researched 2026-10-06.

## Primary sources

- `androidx.car.app:app:1.7.0` and `app-projected:1.7.0` AARs from `~/.gradle/caches`, decompiled with jadx. Every signature and transaction number below was verified against the compiled 1.7.0 stubs (`javap -p -constants`).
- AndroidX `androidx-main` source (commit `a293caeb`, 2026-10-05), `car/app/app*/src/main/stableAidl`.
- Newer AARs from Google Maven for diffs: `1.8.0-alpha01/02/03`, `1.8.0-beta01`, `1.8.0-rc01`, `1.9.0-alpha01/02`.
- **AOSP reference template host**: `https://android.googlesource.com/platform/packages/apps/Car/Templates` (`Host/app/apphost`, package `com.android.car.libraries.apphost`, Apache-2.0), built on `androidx.car.app:app:1.2.0-beta02`. Google's Android Auto (gearhead) ships the same `com.android.car.libraries.apphost` package. Best design reference for the host side.
- Android Auto 17.7 manifest (`com.google.android.projection.gearhead`), dumped with aapt2.
- Source of CoMaps, Organic Maps, Tempus, and [Lapin](https://github.com/prototux/lapin).

Notation: AIDL `= n` is the method id in the `.aidl`; the Java constant is `TRANSACTION_x = FIRST_CALL_TRANSACTION + (n-1)`.

---

## 0. TL;DR

1. **Practical route:** the host depends on `androidx.car.app:app` + `app-projected` and reuses the AIDL stubs, `Bundleable`/`Bundler`, all model classes, `HandshakeInfo`, `AppInfo`, `SurfaceContainer`, `OpenMicrophoneResponse`. The AOSP host does exactly this. The wire format is reflection-based (Bundle keys are `declaringClassName + fieldName`), so hand-decoding without the library is possible but pointless.
2. **Handshake order** (host drives the app): `bindService` -> `getAppInfo` -> `onHandshakeCompleted` -> `onAppCreate` -> `getManager("app")` -> `IAppManager.getTemplate` -> `onAppStart` -> `onAppResume`.
3. **`HostValidator` is the access gate** and is the single biggest integration risk; see section 5.
4. **Coexistence with Android Auto:** gearhead defines `androidx.car.app.ACCESS_SURFACE / NAVIGATION_TEMPLATES / MAP_TEMPLATES` (normal protection) and the provider authority `androidx.car.app.connection`. The host must NOT redeclare any of them or installation conflicts with AA.
5. **The host must implement `ICarHost.getHost("hardware")`** returning an `ICarHardwareHost`, or `CarSensors` listeners throw `HostException` inside nav apps (CoMaps and Organic Maps both hit this).
6. **Microphone:** `IAppHost.openMicrophone` returns a pipe FD; the host writes 16 kHz mono 16-bit PCM (`audio/l16`).

---

## 1. Discovery and manifest contract

### 1.1 Template apps (Car App Library)

```kotlin
val intent = Intent("androidx.car.app.CarAppService")   // CarAppService.SERVICE_INTERFACE
pm.queryIntentServices(intent, GET_META_DATA or GET_RESOLVED_FILTER)
```

Categories on the same intent-filter as the action:

| Category constant (string) | Since API | Notes |
|---|---|---|
| `androidx.car.app.category.NAVIGATION` | 1 | Gets `INavigationHost`; may use `NavigationTemplate`; target of `ACTION_NAVIGATE`. |
| `androidx.car.app.category.POI` | 1 | Replaces PARKING/CHARGING. |
| `androidx.car.app.category.PARKING`, `...CHARGING` | 1 | `@Deprecated` (use POI). |
| `androidx.car.app.category.IOT` | experimental | Lapin uses this. |
| `androidx.car.app.category.WEATHER` | experimental | |
| `androidx.car.app.category.MESSAGING`, `...CALLING` | experimental | gearhead registers MESSAGING CarAppServices. |
| `androidx.car.app.category.SETTINGS` | experimental | |
| `androidx.car.app.category.FEATURE_CLUSTER` | 6 | Combined with NAVIGATION; app supports a second (cluster) session. |

Constants are in `CarAppService` (`CATEGORY_NAVIGATION_APP`, `CATEGORY_POI_APP`, `CATEGORY_IOT_APP`, ...).

### 1.2 Manifest meta-data the app declares

- `androidx.car.app.minCarApiLevel` (int) on `<application>`. Required; `AppInfo.retrieveMinCarAppApiLevel` throws if missing/0. Key const: `AppInfo.MIN_API_LEVEL_METADATA_KEY`.
- `com.google.android.gms.car.application` -> `@xml/automotive_app_desc`, an `<automotiveApp>` with `<uses name="template"/>` (nav/template apps) or `<uses name="media"/>` (media apps). AA uses this for app-type discovery.
- `androidx.car.app.CarAppMetadataHolderService.CAR_HARDWARE_MANAGER` = `androidx.car.app.hardware.ProjectedCarHardwareManager` is injected by the **app-projected** AAR manifest; the app's `CarHardwareManager.create` reads it reflectively. (The automotive variant injects `RESULT_MANAGER` similarly.)
- `androidx.car.app.TintableAttributionIcon` (media apps; seen in gearhead + Tempus).

### 1.3 Permissions apps declare (all `<uses-permission>`), defined by the host/AA

- `androidx.car.app.ACCESS_SURFACE` - required to call `IAppHost.setSurfaceCallback` (host enforces via `CarAppPermission.checkHasLibraryPermission`, which also accepts a merely-requested permission).
- `androidx.car.app.NAVIGATION_TEMPLATES` - nav apps.
- `androidx.car.app.MAP_TEMPLATES` - map-content apps.

These three are **defined by** the host/AA (gearhead declares them `protectionLevel=normal`). A navigation host (ours) is the natural definer, but AA already defines them (section 5.4).

---

## 2. Lifecycle the host must drive

### 2.1 Bind

`Intent` with action `androidx.car.app.CarAppService`, component = resolved service. The AOSP host binds with `BIND_AUTO_CREATE | BIND_INCLUDE_CAPABILITIES`. `onServiceConnected` gives an `IBinder`; `ICarApp.Stub.asInterface(binder)`.

Bind-intent extras:
- The host wraps the launch intent so the *app's* real intent is carried under `com.android.car.libraries.apphost.common.ORIGINAL_INTENT` (`IntentUtils.embedOriginalIntent/extractOriginalIntent`). The Intent later passed to `onAppCreate` is the original.
- **Multi-display / cluster (API 6+):** `SessionInfoIntentEncoder.encode` puts a bundle under extra `androidx.car.app.extra.SESSION_INFO_BUNDLE` with ints `display-type` (0 MAIN, 1 CLUSTER) and string `session-id`, and sets `Intent.setIdentifier(sessionInfo.toString())` (API 29+) or a data URI. `CarAppService.onBind` keys one `CarAppBinder` per `SessionInfo`; without the extra it uses `DEFAULT_SESSION_INFO = (0,"main")`. Send it only for API>=6 apps.

### 2.2 `ICarApp` (app binder) - 1.7.0 AIDL, `oneway`

```
onAppCreate(ICarHost host, in Intent intent, in Configuration configuration, IOnDoneCallback cb)  = 1  (TRANSACTION=2)
onAppStart(IOnDoneCallback cb)             = 2
onAppResume(IOnDoneCallback cb)            = 3
onAppPause(IOnDoneCallback cb)             = 4
onAppStop(IOnDoneCallback cb)              = 5
onNewIntent(in Intent intent, IOnDoneCallback cb)                = 6
onConfigurationChanged(in Configuration, IOnDoneCallback cb)     = 7
getManager(in String type, IOnDoneCallback cb)                   = 8
getAppInfo(IOnDoneCallback cb)             = 9
onHandshakeCompleted(in Bundleable handshakeInfo, IOnDoneCallback cb) = 10
// 1.9.0-alpha02 adds: getAppTheme(IOnDoneCallback cb) = 11 (ICarApp level); pulls app's preferred theme style int.
```

The sequence the AOSP host runs after `onServiceConnected` (`CarAppBinding`):
1. **`getAppInfo`** -> `AppInfo` (minCarAppApiLevel, latestCarAppApiLevel, libraryVersion string).
2. Negotiate (`CarHostConfig.updateNegotiatedApi`): `negotiated = min(appLatest, hostMax)`; error if `appMin > hostMax` or `hostMin > appLatest`.
3. **`onHandshakeCompleted(Bundleable.create(new HandshakeInfo(hostPackageName, negotiatedApiLevel)))`**. The app re-validates: it runs `HostValidator.isValidHost` here (using `Binder.getCallingUid()` + the package name from HandshakeInfo) and checks `appMin <= hostApi <= appLatest`. **This is where a rejected host fails** (`onFailure` with `IllegalArgumentException("Unknown host ...")`).
4. **`onAppCreate(ICarHost, originalIntent, resources.getConfiguration(), cb)`**. App creates its `Session`, pushes the root `Screen` (`Session.onCreateScreen(intent)`).
5. `getManager("app")` -> `IAppManager`. `getManager("navigation")` -> `INavigationManager` (only for nav apps). Returned via `IOnDoneCallback.onSuccess(Bundleable<IInterface>)`.
6. Lifecycle events `onAppStart` (visible) -> `onAppResume` (active). `onAppPause` / `onAppStop` on backgrounding. On ON_STOP the AOSP host schedules an unbind after `getAppUnbindSeconds`.

`Configuration` passed in `onAppCreate` drives a `VirtualDisplay` inside `CarContext.attachBaseContext` (size = `screenWidthDp/HeightDp`, `densityDpi`). On screen/UI change call `onConfigurationChanged(Configuration, cb)`.

**ANR:** AOSP host arms a 5000 ms timer (`ANRHandler.ANR_TIMEOUT_MS`) per dispatched call, dismissed by the `IOnDoneCallback`.

### 2.3 `ICarHost` (host binder the host passes into `onAppCreate`) - NOT `oneway`

```
startCarApp(in Intent intent)   = 1   // app asks host to launch a car activity (e.g. ACTION_NAVIGATE, geo:)
IBinder getHost(in String type) = 2   // type in {"app","navigation","constraints","suggestion","media_playback","hardware","car"}
finish()                        = 3
```

`getHost(type)` returns the sub-host binders below. The app's `HostDispatcher` caches each. AOSP `CarHost.getHost` gates: `navigation` only if app is a nav app; `constraints` requires negotiated API>=2; `hardware` requires API>=3; else `IllegalArgumentException`.

### 2.4 `IAppHost` (`getHost("app")`) - NOT `oneway`

```
invalidate()                                          = 1   // app requests re-fetch of template
showToast(CharSequence text, int duration)            = 2   // duration 0 SHORT / 1 LONG
setSurfaceCallback(@nullable ISurfaceCallback cb)      = 3   // host checks app holds ACCESS_SURFACE
sendLocation(in Location location)                     = 4
showAlert(in Bundleable alert)                         = 5   // API 5
dismissAlert(int alertId)                              = 6   // API 5
Bundleable openMicrophone(in Bundleable req)           = 7   // API 5, SYNCHRONOUS return
```

`invalidate` -> host calls `IAppManager.getTemplate`. The host must implement all of these.

### 2.5 `IAppManager` (`getManager("app")`) - `oneway`

```
getTemplate(IOnDoneCallback cb)          = 1   // cb.onSuccess(Bundleable<TemplateWrapper>)
onBackPressed(IOnDoneCallback cb)        = 2
startLocationUpdates(IOnDoneCallback cb) = 3   // app pushes fused location to host via IAppHost.sendLocation
stopLocationUpdates(IOnDoneCallback cb)  = 4
```

### 2.6 `INavigationManager` (`getManager("navigation")`) - `oneway`

```
onStopNavigation(IOnDoneCallback cb) = 1
```

### 2.7 `INavigationHost` (`getHost("navigation")`) - NOT `oneway`

```
navigationStarted()                = 1
navigationEnded()                  = 2
updateTrip(in Bundleable trip)     = 3   // Trip model -> turn/ETA for nav template + cluster
// 1.9.0-alpha02 adds setVoiceAssistantCapabilities(in Bundleable) = 4
```

### 2.8 `IConstraintHost` (`getHost("constraints")`, API 2) - NOT `oneway`

```
int  getContentLimit(int contentType)   = 1   // contentType: 0 LIST,1 GRID,2 PLACE_LIST,3 ROUTE_LIST,4 PANE
boolean isAppDrivenRefreshEnabled()     = 2   // API 6
```

If the host returns nothing the app falls back to library default integers (list 6, grid 6, place_list 6, route_list 3, pane 4).

### 2.9 `ICarHardwareHost` (`getHost("hardware")`, app-projected, API 3) - NOT `oneway`

```
getCarHardwareResult(int resultType, in @nullable Bundleable params, ICarHardwareResult cb)       = 1
subscribeCarHardwareResult(int resultType, in @nullable Bundleable params, ICarHardwareResult cb) = 2
unsubscribeCarHardwareResult(int resultType, in @nullable Bundleable params)                      = 3
```

`ICarHardwareResult` (app callback, `oneway`): `onCarHardwareResult(int resultType, boolean isSupported, in @nullable Bundleable result, in IBinder doneCallback)`. The `IBinder` is an `IOnDoneCallback`; the app ACKs on it. `resultType` consts (`ICarHardwareResultTypes`): INFO_MODEL 1, ENERGY_PROFILE 2, TOLL 3, ENERGY_LEVEL 4, SPEED 5, MILEAGE 6, EV_STATUS 7, SENSOR_ACCELEROMETER 20, COMPASS 21, GYROSCOPE 22, **CAR_LOCATION 23**. For a type we can't supply, reply `onCarHardwareResult(type, false, null, doneCb)`; the app substitutes an UNIMPLEMENTED `CarValue`. Returning a null host binder throws `HostException` in the app.

### 2.10 Other sub-hosts

- `ISuggestionHost` (`getHost("suggestion")`, API 5): `updateSuggestions(in Bundleable)` = 1.
- `IMediaPlaybackHost` (`getHost("media_playback")`): `registerMediaSessionToken(in Bundleable token)` = 1 (first-call, id=1, not the usual +1). Used by MediaPlaybackTemplate apps (API 8) to hand the host a MediaSession token.

### 2.11 `IOnDoneCallback` - the universal ACK

```
onSuccess(in @nullable Bundleable response) = 1
onFailure(in Bundleable failureResponse)    = 2   // Bundleable<FailureResponse>
```

Every app->host and host->app request with a callback uses this. `RemoteUtils.dispatchCallFromHost` on the app side runs the work on the main thread, checks the Session lifecycle is >= CREATED, then calls `onSuccess`/`onFailure`. `FailureResponse` carries a stack-trace string + `errorType` (UNKNOWN 0, BUNDLER 1, ILLEGAL_STATE 2, INVALID_PARAMETER 3, SECURITY 4, RUNTIME 5, REMOTE 6). The host should treat a missing ACK within 5 s as an ANR.

---

## 3. Templates, serialization and callbacks

### 3.1 Retrieval

`IAppManager.getTemplate(cb)` -> `cb.onSuccess(Bundleable b)`, `b.get()` is a `TemplateWrapper` (app side: `ScreenManager.getTopTemplate` wraps the top `Screen.onGetTemplate()`). `TemplateWrapper` fields: `mTemplate` (the `Template`), `mId` (string; stable across refresh so the host can diff), `mTemplateInfoForScreenStack` (List<TemplateInfo>{templateClass, id} for the whole back stack), `mCurrentTaskStep`, `mIsRefresh`. The host asks again whenever the app calls `IAppHost.invalidate()`.

### 3.2 Bundler / Bundleable wire format

`Bundleable` is a `@JavaOnlyStableParcelable` holding one `Bundle`; `Bundleable.create(obj)` = `Bundler.toBundle`, `.get()` = `Bundler.fromBundle`. `Bundler` (reflection) fields:

- `tag_class_type` (int): 0 PRIMITIVE, 1 IINTERFACE, 2 MAP, 3 SET, 4 LIST, 5 OBJECT, 6 IMAGE(IconCompat), 7 ENUM, 8 CLASS, 9 IBINDER, 10 PERSON.
- `tag_class_name` (string): fully-qualified class name (for OBJECT/ENUM/IINTERFACE).
- `tag_value`: the payload (primitive, Parcelable, binder, or a `ParcelableArrayList<Bundle>` for collections). Maps use entry bundles with `tag_1`/`tag_2`.
- **OBJECT fields:** each non-static field is stored under key `getDeclaringClassName() + fieldName` (e.g. `androidx.car.app.model.RowmTitle`), value = nested Bundle. Deserialize requires the class be `@CarProtocol` and have a no-arg constructor; the field map is read back by the same key. There is an IconCompat androidx/support classname fallback.
- IINTERFACE: stores `asBinder()` + class name; deserialize calls `ClassName.asInterface(binder)` reflectively. This is how `OnClickListener` etc. cross the boundary (see 3.4).

**Consequence for the host:** depend on `androidx.car.app` and call `Bundleable.get()` with the library's own model classes on the classpath. The consumer ProGuard rules in the AAR (`-keepclasseswithmembers @androidx.car.app.annotations.KeepFields class * {*;}` and `-keep class androidx.car.app.** extends android.os.IInterface`) must be kept so field names survive R8. All model classes are `@KeepFields @CarProtocol`.

### 3.3 Template types in 1.7.0 and the API level that introduced each

| Template | Package | Since |
|---|---|---|
| `PaneTemplate` | model | 1 |
| `ListTemplate` | model | 1 |
| `GridTemplate` | model | 1 |
| `MessageTemplate` | model | 1 |
| `SearchTemplate` | model | 1 |
| `PlaceListMapTemplate` | model | 1 |
| `NavigationTemplate` | navigation.model | 1 |
| `PlaceListNavigationTemplate` | navigation.model | 1 (`@Deprecated`) |
| `RoutePreviewNavigationTemplate` | navigation.model | 1 (`@Deprecated`) |
| `LongMessageTemplate` | model | 2 |
| `SignInTemplate` | model.signin | 2 |
| `MapTemplate` | navigation.model | 5 (`@Deprecated`, superseded by MapWithContentTemplate) |
| `TabTemplate` | model | 6 |
| `MapWithContentTemplate` | navigation.model | 7 |
| `MediaPlaybackTemplate` | media.model | 8 (`@ExperimentalCarApi`) |
| `SectionedItemTemplate` | model | `@ExperimentalCarApi` (no `@RequiresCarApi` yet in 1.7.0) |

1.8/1.9 add (not in 1.7.0, but a forward-looking host should expect them): `InCallTemplate`, `DialpadTemplate`/`TelephoneKeypadTemplate` (dialer), and model additions `Banner`, `Chip`, `CarProgressBar`, `Background`, `SearchHeader`, `SectionHeader`, `SpotlightSection`, `MediaPlaybackStyle`, `NavigationVoiceAssistantCapabilities`.

Cluster display (`SessionInfo` DISPLAY_TYPE_CLUSTER) supports only `NavigationTemplate` (API 6+).

### 3.4 Callbacks flowing app -> host (delegates)

Interactive model elements embed an `IInterface` stub serialized by Bundler; when the user acts, the host invokes it and the app ACKs via a nested `IOnDoneCallback`:

- `OnClickDelegate` wraps `IOnClickListener.onClick(IOnDoneCallback)` = 1. `OnClickDelegateImpl.isParkedOnly()` is a plain serialized boolean: true when the app wrapped the listener in `ParkedOnlyOnClickListener`. **The host must refuse / gate those actions while driving.**
- `IOnSelectedListener.onSelected(int index, cb)` - ItemList radio selection.
- `IOnItemVisibilityChangedListener.onItemVisibilityChanged(int startIncl, int endExcl, cb)` - the host reports which list rows are visible (for lazy loading).
- `IOnCheckedChangeListener.onCheckedChange(boolean, cb)` - Toggle.
- `IOnContentRefreshListener.onContentRefreshRequested(cb)` - pull-to-refresh header.
- `ISearchCallback.onSearchTextChanged(String,cb)` / `onSearchSubmitted(String,cb)` - SearchTemplate. `SearchCallbackDelegate` wraps it.
- `IInputCallback.onInputTextChanged` / `onInputSubmitted` - SignIn input.
- `ITabCallback.onTabSelected(String tabContentId, cb)` - TabTemplate (API 6).
- `IAlertCallback.onAlertCancelled(int reason, cb)` / `onAlertDismissed(cb)` - reasons: TIMEOUT 1, USER_ACTION 2, NOT_SUPPORTED 3.
- `IPanModeListener.onPanModeChanged(boolean, cb)` - nav pan mode.
- `IOnDoneCallback` from these flows is created by `RemoteUtils.createOnDoneCallbackStub`.
- Large lists: `ListDelegate` / `IRemoteList.requestItemRange(start, end, cb)` lets the host page items.
- Messaging: `IConversationCallback.onMarkAsRead(cb)` / `onTextReply(cb, String replyText)` (API 7).

Action constants (`model.Action`): `TYPE_APP_ICON` 65538, `TYPE_BACK` 65539, `TYPE_PAN` 65540, `TYPE_COMPOSE_MESSAGE` 65541; flags `FLAG_PRIMARY` 1, `FLAG_IS_PERSISTENT` 2, `FLAG_DEFAULT` 4.

### 3.5 Distraction / template-flow rules (host responsibility)

The AOSP host enforces a **task-step limit** (`TemplateValidator`, configurable `mStepLimit`, default 5 for template task flows) and per-template content limits and allowed-template checks (`distraction/checkers/*`). A template of a **new class** starts/continues a task step; a refresh (same class, same id or `isRefresh`) does not. Over the limit -> `OverLimitFlowViolationException`. A real host should mirror this to behave like AA.

---

## 4. Surface (map rendering)

`ISurfaceCallback` (host -> app, `oneway`), 1.7.0:

```
onSurfaceAvailable(in Bundleable surfaceContainer, IOnDoneCallback cb)  = 1
onVisibleAreaChanged(in Rect visibleArea, IOnDoneCallback cb)           = 2
onStableAreaChanged(in Rect stableArea, IOnDoneCallback cb)             = 3
onSurfaceDestroyed(in Bundleable surfaceContainer, IOnDoneCallback cb)  = 4
onScroll(float distanceX, float distanceY)   = 5   // no cb; API 2
onFling(float velocityX, float velocityY)    = 6   // API 2
onScale(float focusX, float focusY, float scaleFactor) = 7  // API 2
onClick(float x, float y)                    = 8   // API 5
```

`SurfaceContainer` (`@CarProtocol`) fields: `mSurface` (`android.view.Surface`), `mWidth`, `mHeight`, `mDpi` (ints). The host creates a real `Surface` (e.g. from a `SurfaceView`/`SurfaceTexture` or a `VirtualDisplay`) and sends it. The app typically wraps it in a `VirtualDisplay` + `Presentation` and draws its GL map into it (that is exactly what CoMaps/Organic Maps `SurfaceCallback.onSurfaceAvailable` does). The Surface instance received by the app must later be `Surface.release()`d.

**Visible vs stable area** (both `Rect` in surface pixels):
- **Visible area** = the rectangle *currently* not occluded by any chrome (dynamic: status bar, action strip, template cards may overlay the surface). The AOSP host computes it in `AbstractSurfaceTemplatePresenter.updateVisibleArea` as the root surface view's bounds minus padding (`left+paddingLeft, top+paddingTop, right-paddingRight, bottom-paddingBottom`) plus any additional inset from visible widgets, then `SurfaceInfoProvider.setVisibleArea(rect)`. It only dispatches after the SurfaceContainer exists and when the area actually changes.
- **Stable area** = the area that stays usable even accounting for chrome that could appear/disappear (treated as always present). For persistent overlays (e.g. a speed pill) use the stable area; for transient critical data use the visible area. The app insets its map UI accordingly (CoMaps/OM call `Framework.nativeSetVisibleRect`).
- Fire `onVisibleAreaChanged`/`onStableAreaChanged` right after `onSurfaceAvailable` and whenever template chrome relayout changes the insets.

Scroll/fling/scale/click come from the host's gesture detector over the surface (AOSP `MapGestureManager` / `PanZoomManager`). `onClick` is API 5. `onScale` focus (-1,-1) means unavailable.

---

## 5. HostValidator: the access-control gate

The app calls `HostValidator.isValidHost(HostInfo)` inside `onHandshakeCompleted` (1.9.0-alpha02 also re-checks every call against a cached authenticated UID; see 5.5). `HostInfo` = `{packageName (from the HandshakeInfo the host sent), uid = Binder.getCallingUid()}`. The validator the app returns comes from `CarAppService.createHostValidator()`.

### 5.1 Exact 1.7.0 logic (`validateHost`, verified from decompiled bytecode)

1. `ALLOW_ALL_HOSTS_VALIDATOR` (the `mAllowAllHosts` flag) -> accept immediately. (Debug builds of CoMaps/OM/Lapin use this.)
2. Result is cached per package+uid.
3. Look up `PackageInfo` for the host package (flags include signatures + requested permissions; API28+ uses `GET_SIGNING_CERTIFICATES | GET_PERMISSIONS`). Not found -> reject.
4. Signatures empty -> reject. `packageInfo.applicationInfo.uid != hostInfo.getUid()` -> throw `IllegalStateException` (anti-spoof).
5. Compute `hasPermission = hasPermissionGranted(packageInfo, "android.car.permission.TEMPLATE_RENDERER")` and `isAllowListed = <pkg,sha256(signature)> in allowlist`.
6. Decision order:
   - `uid == Process.myUid()` (same uid as the app, e.g. same `sharedUserId`) -> accept ("Local service call").
   - `isAllowListed` -> accept.
   - `uid == 1000` (system/`android.uid.system`) -> accept ("System binding").
   - `hasPermission` -> accept ("Host has android.car.permission.TEMPLATE_RENDERER").
   - else reject with a log telling the dev to add our `<pkg, digest>` to `createHostValidator()`.

`hasPermissionGranted`: iterates `packageInfo.requestedPermissions[]` and requires `(requestedPermissionsFlags[i] & REQUESTED_PERMISSION_GRANTED(=2)) != 0` **and** the name equals TEMPLATE_RENDERER. So the permission must be *granted*, not merely requested. `TEMPLATE_RENDERER_PERMISSION = "android.car.permission.TEMPLATE_RENDERER"`.

Digest = lowercase hex SHA-256 of `Signature.toByteArray()` (the DER cert). Allowlist string-array format: `"<sha256hex>,<packageName>"` per `<item>` (whitespace stripped, lowercased). `addAllowedHosts(@ArrayRes)` parses it.

### 5.2 The default sample allowlist `androidx.car.app.R.array.hosts_allowlist_sample`

Six Google entries only (2 package names, 3 signing certs each):
- `com.google.android.projection.gearhead` (Android Auto) digests `fdb00c43...`, `70811a3e...`, `1975b2f1...`
- `com.google.android.apps.automotive.templates.host` (AAOS host) digests `c241ffbc...`, `dd66deaf...`, `50e603d3...`

No phone third-party host is in it. CoMaps, Organic Maps and Lapin all ship exactly this list in release builds, so by default **none of them will accept our host**.

### 5.3 The TEMPLATE_RENDERER approach, and why it is only a short-term bridge

On a phone, `android.car.permission.TEMPLATE_RENDERER` is normally undefined (it is a system permission only present on Android Automotive API 32+, defined by the AAOS templates host; AA/gearhead does **not** define it - confirmed in the 17.7 manifest). So on older library versions a host app that itself `<permission ... android:name="android.car.permission.TEMPLATE_RENDERER" android:protectionLevel="normal"/>` and `<uses-permission>`s it will, for its own package, have the permission *granted* (a self-defined normal permission is auto-granted at install). Then `hasPermissionGranted(ourPackageInfo, TEMPLATE_RENDERER)` is true and step 6 accepts us. Verified reachable in 1.7.0, 1.8.0-alpha/beta, 1.9.0-alpha01.

**This is a known vulnerability Google has patched.** androidx commit `c14b0d6576` (2026-07-07, "Gate TEMPLATE_RENDERER permission validation on FEATURE_AUTOMOTIVE", Bug 527175447) changes step 5 to:

```java
boolean hasPermission = this.mIsAutomotiveDevice
    && hasPermissionGranted(packageInfo, TEMPLATE_RENDERER_PERMISSION);
// mIsAutomotiveDevice = packageManager.hasSystemFeature("android.hardware.type.automotive")
```

On a phone `hasSystemFeature(FEATURE_AUTOMOTIVE)` is false, so the permission path is disabled entirely. The fix ships in **1.8.0-rc01 (2026-08-26)** and **1.9.0-alpha02 (2026-09-09)** (NOT in 1.8.0-beta01 or 1.9.0-alpha01). Any target app that compiles against >= those versions can no longer be reached this way. So:
- It works **today** against CoMaps (1.7.0), Organic Maps (1.8.0-alpha02), Lapin (1.7.0).
- It will **break** the moment any of them bump the car-app library past the fix. Organic Maps pins `1.8.0-alpha02` deliberately (comment: 1.8.0-alpha03 needs minSdk 23); whenever they move to >=rc01 it stops.
- Do not rely on it as the only mechanism.

### 5.4 Install-conflict constraints for the permission trick (and in general)

- Declaring `<permission android:name="android.car.permission.TEMPLATE_RENDERER">` is fine on a phone where nothing else defines it. But if any installed package already defines it (an AAOS-style host, or a future AA build), install fails with `INSTALL_FAILED_DUPLICATE_PERMISSION` unless same signer. Mitigation: the host could instead ship without defining it and only work where some other package defines it — but then self-grant doesn't happen. Net: defining it ourselves is required for the trick and is the thing that risks the duplicate-permission conflict.
- **Separately and unconditionally**, our host must NOT declare `androidx.car.app.ACCESS_SURFACE`, `androidx.car.app.NAVIGATION_TEMPLATES`, or `androidx.car.app.MAP_TEMPLATES` as `<permission>` (gearhead defines all three, normal protection) and must NOT register a provider with authority `androidx.car.app.connection` (gearhead owns it) — either would block installation alongside Android Auto (`INSTALL_FAILED_DUPLICATE_PERMISSION` / `...CONFLICTING_PROVIDER`). The host may still `<uses-permission>` them.
- Google Play Services (GMS) typically does NOT define TEMPLATE_RENDERER (it's an AAOS platform permission). No conflict expected from GMS, but if GMS or a future AA ever defines it, revert to the allowlist path.

### 5.5 The durable solution (recommended primary mechanism)

Get our host's signing-certificate SHA-256 digest into each app's `HostValidator`. Options, in order of leverage:
1. **Upstream a patch** to CoMaps/Organic Maps/Tempus `createHostValidator()` adding our `<package, digest>` (or an allowlist resource they bundle). All three currently hardcode only the Google sample array; they would need to add `.addAllowedHost("net.prototux.aare", "<ourDigest>")`.
2. For apps we build/sign ourselves (Lapin), add the host to the allowlist directly — Lapin already exposes `car_hosts_allowlist`; we add our digest there.
3. If an app can't be changed and is on a patched library, the only remaining accepted paths are same-uid (`sharedUserId` — impractical across signers) or uid 1000 (system app — only if our host is a privileged/system app, which is the realistic long-term answer for an AOSP-based build).

1.9.0-alpha02 hardens further (`CarAppBinder`): it records `mAuthenticatedUid` at handshake, validates `getPackagesForUid(uid)` against the claimed package (`isValidClaimedPackage`), rejects a second host taking over (`onHandshakeAlreadyCompletedException`), and rejects any later call whose `Binder.getCallingUid()` != authenticated uid. None of this changes the accept criteria, but it means the host must present a consistent package/uid and complete the handshake before any other call.

---

## 6. What each target app needs from the host

Common to all template apps: correct handshake, a `HostValidator` acceptance (section 5), an `IAppHost` (invalidate/showToast/setSurfaceCallback at least), and `IConstraintHost`.

### 6.1 CoMaps (codeberg.org/comaps/comaps, pkg `app.comaps[.google|.fdroid]`)
- Library `androidx.car.app 1.7.0` (+ app-projected). `minCarApiLevel = 5`. Category `NAVIGATION`; also an intent-filter for `androidx.car.app.action.NAVIGATE` / `geo:`.
- `createHostValidator()`: DEBUG -> ALLOW_ALL; release -> `addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)` (Google only). Code at `android/app/src/main/java/app/organicmaps/car/CarAppService.java` (CoMaps kept the `app.organicmaps` package path).
- Requires from host: **Surface** (`SurfaceRenderer`/`SurfaceCallback` -> `createVirtualDisplay` + `Presentation`; needs `onSurfaceAvailable` + visible/stable areas), **NavigationManager** (`updateTrip`, `navigationStarted/Ended`), **ConstraintManager** (content limits), and **CarHardwareManager** `CarSensors` — `CarSensorsManager` calls `addCompassListener` + `addCarHardwareLocationListener` (types COMPASS 21, CAR_LOCATION 23) **without catching** — so the host MUST provide `ICarHardwareHost` and answer at least `false/unimplemented`. Uses `MapWithContentTemplate`, `NavigationTemplate`, `ListTemplate`, `GridTemplate`, `PaneTemplate`, `MessageTemplate`, `SearchTemplate`, tabs (API 6 path guarded). `<uses-permission>` ACCESS_SURFACE, NAVIGATION_TEMPLATES. Flavors google/fdroid differ only in location backend (microG), not in car code.

### 6.2 Organic Maps (github.com/organicmaps/organicmaps, pkg `app.organicmaps`)
- Library pinned `androidx.car.app 1.8.0-alpha02` (deliberately, for minSdk 21). `minCarApiLevel = 7`. Category `NAVIGATION` + NAVIGATE/geo filter. Car code split into `:libs:car` and `:sdk:car` modules; service `app.organicmaps.car.AndroidAutoService` extends `CarAppServiceBase`.
- `createHostValidator()`: identical pattern (DEBUG ALLOW_ALL, else Google sample array).
- Same host requirements as CoMaps, but **`CarSensorsSafe`** wraps sensor calls and catches only `SecurityException` (not `HostException`/RuntimeException) — so a host returning a null hardware binder still crashes it; provide the hardware host. Because minCarApiLevel=7 the host must negotiate **>= 7** (MapWithContentTemplate). No separate fdroid car exclusion — the fdroid flavor keeps Android Auto; it differs only in location provider.
- When OM moves to library >= 1.8.0-rc01 the TEMPLATE_RENDERER path dies; allowlist/system-app route needed.

### 6.3 Tempus (media app, github.com/eddyizm/tempus, pkg `com.eddyizm.tempus` / `...degoogled`)
- **Not** a Car App Library app — it is a **media3 `MediaLibraryService`** (`androidx.media3:media3-session`, see section 8). Manifest service action `androidx.media3.session.MediaLibraryService` + legacy `android.media.browse.MediaBrowserService`; `automotive_app_desc` has `<uses name="media"/>`.
- Caller validation: it relies on **media3's built-in** controller-package recognition (media3 `MediaSessionImpl` hardcodes `com.google.android.projection.gearhead`, `com.android.car.media`, `com.android.car.carlauncher`). `onConnect` in `BaseSessionCallback` branches on `session.isAutomotiveController()` / `isAutoCompanionController()` / `isMediaNotificationController()`. **For our host's media controller to get the Auto treatment, its package must be recognized as an automotive controller** — i.e. media3 must classify us. There is no app-level allowlist to patch; the gate is in media3. See section 8 for how media3 decides.
- Needs from host: a `MediaController`/`MediaBrowser` connection, browse tree via `onGetLibraryRoot`/`onGetChildren` (sets CONTENT_STYLE extras through `androidx.media.utils.MediaConstants`), `onSearch`/`onGetSearchResult`, and transport via the MediaSession. No Surface, no car hardware.

### 6.4 Lapin (pkg `net.prototux.lapin`)
- Library `androidx.car.app 1.7.0` + app-projected. `minCarApiLevel = 1`. Category **IOT** (experimental). Service `.car.LapinCarAppService`.
- `createHostValidator()`: DEBUG -> ALLOW_ALL; release -> `addAllowedHosts(R.array.car_hosts_allowlist)` which is the **same 6 Google entries** copied locally. Since we build Lapin, we can add our host digest here directly — the easy case.
- Uses `MessageTemplate` with a `Header` (`setHeader` is API 7; `setLoading` API 2) and up to two `Action`s. Guards every richer feature behind `carContext.carAppApiLevel >= LEVEL_5`. So the host should negotiate **API >= 5** for Lapin's mic flow; at <5 Lapin degrades to a toast.
- **Needs the microphone** (section 7): `CarMic` -> `CarAudioRecord.create(carContext).startRecording()` on car API >= 5; reads 16 kHz mono s16le. It also calls `carContext.requestPermissions([RECORD_AUDIO])` (host must drive the permission activity, section 2 / `CarContext.requestPermissions`) and `startCarApp(ACTION_NAVIGATE, geo:...)` to hand navigation to a nav app. `MessageTemplate` only — no Surface, no NavigationManager/CarHardware.

**Note on MessageTemplate `setHeader`:** it is `@RequiresCarApi(7)`. Lapin targets minApi 1 but calls `setHeader` unconditionally; at a negotiated level < 7 the app's own builder throws. The host negotiating >= 7 avoids that; something to flag to Lapin.

---

## 7. Microphone (feeding the car mic to apps like Lapin)

Projected path: `CarAudioRecord.create(ctx)` returns `ProjectedCarAudioRecord` (chosen reflectively unless `isAutomotiveOS`). `startRecording()` calls `AppManager.openMicrophone(OpenMicrophoneRequest)` which does a **synchronous** `IAppHost.openMicrophone(Bundleable<OpenMicrophoneRequest>)` and expects `Bundleable<OpenMicrophoneResponse>` back.

- `OpenMicrophoneRequest` (API 5, `@CarProtocol`): one field, a `CarAudioCallbackDelegate` wrapping an `ICarAudioCallback` stub. `ICarAudioCallback.onStopRecording()` — the host calls it (through the delegate) to tell the app recording stopped.
- `OpenMicrophoneResponse` (API 5): fields `mCarAudioCallbackDelegate` (host's own `CarAudioCallback` stub, so the app can tell the host it stopped) and `mCarMicrophoneDescriptor` (`ParcelFileDescriptor`). **The host builds it:** `new OpenMicrophoneResponse.Builder(hostCarAudioCallback).setCarMicrophoneDescriptor(readEndPfd).build()`.
- The host creates a pipe (`ParcelFileDescriptor.createReliablePipe()`), returns the **read** end in the response, keeps the **write** end, and streams mic PCM into it. `ProjectedCarAudioRecord` turns the read end into an `AutoCloseInputStream` and the app's `read(byte[],off,len)` reads from it.
- Format (constants in `CarAudioRecord`): `AUDIO_CONTENT_SAMPLING_RATE = 16000`, `AUDIO_CONTENT_BUFFER_SIZE = 512`, `AUDIO_CONTENT_MIME = "audio/l16"`. Mono, 16-bit. `audio/l16` is nominally big-endian, but the Android implementation and Lapin treat it as native `AudioRecord` output = **little-endian s16**; feed little-endian.
- Stop semantics: when the app stops it calls `request`'s callback `onStopRecording` (RECORDSTATE 2). When the host wants to stop it calls the `response`'s callback. The host closes its write end to signal EOF (`read` returns -1).
- The app holds `android.permission.RECORD_AUDIO` itself; `startRecording` requires it. The host also needs RECORD_AUDIO to capture, and must route the permission grant through `CarContext.requestPermissions` -> `CarAppPermissionActivity` (host launches the REQUEST_PERMISSIONS activity; `IOnRequestPermissionsListener.onRequestPermissionsResult(approved[], rejected[])`).

---

## 8. Media apps (MediaBrowserService / media3 MediaLibraryService)

A media app is discovered by its service (`androidx.media3.session.MediaLibraryService` and/or legacy `android.media.browse.MediaBrowserService`) plus `automotive_app_desc` `<uses name="media"/>`. The host acts as a **MediaBrowser/MediaController client** — this is the standard MediaBrowserServiceCompat/MediaController protocol, independent of the Car App Library.

### 8.1 Connection / root hints the host sends
Via `MediaBrowser` connection hints / BrowserRoot rootHints (keys from `androidx.media.utils.MediaConstants` and `android.service.media`):
- `androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT` (int) and `...KEY_ROOT_CHILDREN_SUPPORTED_FLAGS` — advertise browse limits/flags.
- `android.media.extras.MEDIA_ART_SIZE_HINT_PIXELS` — preferred art size.
- `android.service.media.extra.SUGGESTED` / `android.media.browse.extra.PAGE`,`PAGE_SIZE` — recents and paging. AA also uses `EXTRA_RECENT` semantics to resume (via `MediaConstants.EXTRAS_KEY` for completion/resume).

### 8.2 Content-style / browse hints the app returns (BrowserRoot extras + per-item description extras)
- `android.media.browse.CONTENT_STYLE_SUPPORTED` (bool, root) — enables content-style.
- `android.media.browse.CONTENT_STYLE_BROWSABLE_HINT` / `CONTENT_STYLE_PLAYABLE_HINT` (int: 1 LIST, 2 GRID, 3 CATEGORY_LIST, 4 CATEGORY_GRID) — list vs grid per level.
- `CONTENT_STYLE_SINGLE_ITEM_HINT`, `CONTENT_STYLE_GROUP_TITLE_HINT` (string), `android.media.browse.SEARCH_SUPPORTED` (bool), `android.media.browse.extra.DOWNLOAD_PROGRESS`.
- Per-item: `android.media.IS_EXPLICIT`, `android.media.extra.PLAYBACK_STATUS` (completion), `androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE`, `android.media.extra.DOWNLOAD_STATUS`.
- Custom browse actions: `androidx.media.utils.extras.CUSTOM_BROWSER_ACTION_ROOT_LIST` etc.
- Error resolution surfacing a Car App Library flow: `androidx.media.PlaybackStateCompat.Extras.ERROR_RESOLUTION_USING_CAR_APP_LIBRARY_INTENT` and `androidx.media.BrowserRoot.Extras.APPLICATION_PREFERENCES_USING_CAR_APP_LIBRARY_INTENT`.
- Transport slot reservations: `android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_NEXT` / `...SKIP_TO_PREVIOUS` / `...QUEUE` (gearhead also has `com.google.android.gms.car.media.*` variants).

Tempus sets these through `MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE/PLAYABLE` with `..._VALUE_CONTENT_STYLE_GRID_ITEM/LIST_ITEM` in `MediaBrowserTree`/`AutomotiveRepository`.

### 8.3 Caller validation risk (which apps gate the host, and how)
Two layers:
- **media3 internal recognition** (`MediaSessionImpl`): hardcoded controller packages `com.google.android.projection.gearhead`, `com.android.car.media`, `com.android.car.carlauncher`. `ControllerInfo.isAutomotiveController()`/`isAutoCompanionController()`/`isMediaNotificationController()` drive which session commands & button layout the app exposes. Our host's media controller package is not in that list, so apps that branch on these (Tempus does) treat us as an ordinary controller. To get the "automotive" treatment the host's **controller package must be one media3 recognizes**, or media3 must be patched; there is no app-side allowlist for this.
- **App-level PackageValidator** (legacy MediaBrowserServiceCompat apps, e.g. UAMP-derived): an `allowed_media_browser_callers.xml` of `<package, signature>` + checks for own uid, system uid, platform signature, `MEDIA_CONTENT_CONTROL`, and enabled notification-listener packages (`NotificationManagerCompat.getEnabledListenerPackages`). Apps built from UAMP do this; Tempus (media3) does **not**. For UAMP-style apps the host can pass by holding `MEDIA_CONTENT_CONTROL` (a privileged permission) or by being an enabled notification listener, or by being added to the xml allowlist. Tempus requires neither.

The host's own `MediaController`/`MediaBrowser` just needs to connect, request root, browse, and issue transport commands; most Subsonic/media3 apps accept any connection for browsing.

---

## 9. Notifications / messaging (CarExtender, MessagingStyle, reply)

Two overlapping mechanisms; the host reads posted notifications (as a `NotificationListenerService`) and renders/reads them aloud.

### 9.1 NotificationCompat.CarExtender (androidx.core) — the legacy "Android Auto" message bubble
Stored under notification extra `android.car.EXTRAS` (`CarExtender.EXTRA_CAR_EXTENDER`). Nested keys: `large_icon`, `app_color`, `car_conversation` (an `UnreadConversation` bundle with `messages` string[], `remote_input` (a `RemoteInput`), `on_reply` (PendingIntent), `on_read` (PendingIntent), `participants`, `timestamp`, `author`, `text`). The host:
1. reads messages, TTS them;
2. to reply, fills the `RemoteInput` (key from the UnreadConversation) and sends the `on_reply` PendingIntent with `RemoteInput.addResultsToIntent`;
3. marks read by sending the `on_read` PendingIntent.

### 9.2 MessagingStyle + semantic actions (modern)
`NotificationCompat.MessagingStyle` notifications with `NotificationCompat.Action`s carrying `android.support.action.semanticAction` (`SEMANTIC_ACTION_REPLY = 1`, `SEMANTIC_ACTION_MARK_AS_READ = 2`). The host finds the REPLY action (which carries a `RemoteInput`) and the MARK_AS_READ action, reads the `MessagingStyle` messages aloud, and replies via `RemoteInput` on the action's PendingIntent. This is what current AA uses; the `CarExtender` path is the older fallback.

### 9.3 Car App Library messaging category (separate)
Apps in `androidx.car.app.category.MESSAGING` serve templates and use `ConversationItem`/`CarMessage` (API 7) with `IConversationCallback.onMarkAsRead`/`onTextReply(cb, replyText)`. gearhead registers its own MESSAGING CarAppServices. This is distinct from notification-based messaging and only relevant if we host CarAppService messaging apps (not required for CoMaps/OM/Tempus/Lapin).

### 9.4 CarAppExtender (androidx.car.app.notification) — launching a CarAppService from a notification
`CarAppExtender` (extra `androidx.car.app.EXTENSIONS`) on a notification lets a CarAppService app surface a car notification whose content intent re-enters the car app. Projected path: `CarPendingIntent.getCarApp` wraps the intent into a broadcast to `CarAppNotificationBroadcastReceiver`, which pulls an `IStartCarApp` binder from extra `androidx.car.app.extra.START_CAR_APP_BINDER_KEY` and calls `IStartCarApp.startCarApp(intent)`. CoMaps uses `CarAppExtender` for its Android-Auto nav notification. The host must supply the `IStartCarApp` binder in the PendingIntent extras for this to work on a phone (projected) setup.

---

## 10. Host-side building blocks (summary checklist)

Implement (reusing the library): `ICarHost` (+ `startCarApp`, `getHost`, `finish`); `IAppHost` (invalidate->getTemplate, showToast, setSurfaceCallback, sendLocation, showAlert/dismissAlert, openMicrophone); `INavigationHost`; `IConstraintHost` (content limits + appDrivenRefresh); `ISurfaceCallback` producer (real Surface + visible/stable areas + gestures); `ICarHardwareHost` (answer unsupported=false at minimum); optionally `ISuggestionHost`, `IMediaPlaybackHost`. Drive `ICarApp` in the order of section 2.2. Provide a real `CarContext`-compatible `Configuration` (screen dp + densityDpi). Enforce a task-step limit + content limits + parked-only gating to behave like AA. Expose the connection state provider `androidx.car.app.connection` (content provider returning column `CarConnectionState` = 2 PROJECTION) if we want `CarConnection` to report connected — but note AA already owns that authority, so only register it when AA is absent.

Car API levels per library version (file `car-app-api.level` in classes.jar): 1.1.0/1.2.0 -> 4, 1.3.0 -> 6, 1.4.0/1.7.0 -> 7..8 (1.7.0 = 8), 1.8.0 -> 8, 1.9.0 -> 9. The host should advertise `hostMax` = the highest it supports and negotiate `min(hostMax, appLatest)`.
