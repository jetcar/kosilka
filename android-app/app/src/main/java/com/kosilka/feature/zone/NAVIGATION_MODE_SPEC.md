# Zone Navigation Mode Spec

## Goal
Provide simple, reliable tap-based navigation in the Zone screen while keeping map editing and map browsing predictable.

## Behavior

### 1) Navigation mode OFF
- Map is scrollable (pan) and zoomable (pinch).
- Tap does not send movement commands.
- Zone editing interactions are enabled.
- Mower is rendered by current position feedback, without forced center lock.

### 2) Navigation mode ON
- Pan and zoom gestures are disabled.
- Tap sends a single `MOVE_TO` command with absolute target coordinates in mm.
- Tap-and-hold updates destination while finger is down, with throttled command rate.
- Mower remains visually fixed at viewport center.
- Map (zone/grid/anchors/destination) moves under mower based on position callbacks from USB/emulator.

### 3) Mode transitions
- Enabling Navigation mode immediately arms tap/tap-hold command sending.
- Disabling Navigation mode immediately restores map pan/zoom.
- `Stop` action sends hold-position command and clears destination marker.

## Data and Coordinate Rules
- Coordinate system: millimeters in world space.
- Map tap conversion: screen -> world mm using current transform.
- Commands must be clamped to map bounds.
- Use absolute targets (`targetXMm`, `targetYMm`), not relative vectors.

## Command Rules
- Tap: one `MOVE_TO`.
- Tap-and-hold: repeated `MOVE_TO` at throttle interval (recommended 120-200 ms).
- Prefer latest pointer position; do not queue stale targets.

## Camera Rules
- Navigation ON: camera center follows latest mower position.
- Navigation OFF: camera becomes user-controlled (pan/zoom).
- If mower position is temporarily unavailable, keep last valid camera center and show status.

## Verification Checklist

### A) Interaction checks
- [ ] With Navigation OFF: pan works.
- [ ] With Navigation OFF: zoom works.
- [ ] With Navigation OFF: taps do not emit `MOVE_TO`.
- [ ] With Navigation ON: pan is blocked.
- [ ] With Navigation ON: zoom is blocked.
- [ ] With Navigation ON: tap emits one `MOVE_TO`.
- [ ] With Navigation ON: tap-and-hold emits throttled `MOVE_TO` updates.

### B) Coordinate correctness
- [ ] Tapping map center sends coordinates near map midpoint.
- [ ] Tapping near corners sends near boundary values.
- [ ] Out-of-range taps clamp to map bounds.

### C) Transport correctness (USB + emulator)
- [x] Emulator command log shows expected `MOVE_TO` payload values in mm.
- [ ] USB transport logs show the same payload values from app.
- [ ] Feedback updates camera-follow behavior in Navigation mode.

### D) UX correctness
- [ ] Mower remains centered while navigating.
- [ ] Map content moves under mower from callbacks.
- [ ] Turning Navigation OFF restores scroll/zoom immediately.
- [x] Stop command clears active destination intent.

## Acceptance Criteria
- Navigation behavior is deterministic and mode-dependent.
- Movement commands are in correct mm coordinates.
- No command flooding during hold.
- Works the same through emulator and USB transport.

## Implementation Tasks

### 1) State and mode wiring
- [x] Add `isNavigationMode` to `ZoneUiState`.
- [x] Add navigation telemetry fields to `ZoneUiState`:
	- `destinationMarker`
	- `lastMoveVectorDxMm`
	- `lastMoveVectorDyMm`
	- `lastMoveDistanceMm`
- [x] Ensure state updates are single-source in `ZoneViewModel`.

### 2) Zone screen controls (`ZoneScreen.kt`)
- [x] Add `Start Navigation` / `Stop Navigation` toggle control.
- [x] Add `Stop Mower` button (sends hold-position command).
- [x] Show vector/distance readout in mm.
- [x] Keep zone editing controls visible; disable corner editing while navigation mode is ON.

### 3) Gesture handling (`ZoneScreen.kt` canvas)
- [ ] Navigation OFF:
	- [x] Enable pan.
	- [x] Enable pinch zoom.
	- [x] Disable movement command send on tap.
- [ ] Navigation ON:
	- [x] Disable pan and pinch zoom.
	- [x] Enable tap-to-send `MOVE_TO`.
	- [x] Enable tap-and-hold to stream updated `MOVE_TO` targets.
	- [x] Add hold throttling (target 120-200 ms).

### 4) Coordinate transforms and camera (`ZoneScreen.kt`)
- [x] Keep mower fixed at viewport center while navigation mode is ON.
- [x] Drive camera center from latest mower position callback.
- [x] Convert screen pointer -> map mm using current camera transform.
- [x] Clamp outgoing target coordinates to map bounds.

### 5) Command dispatch and stop behavior (`ZoneViewModel.kt`)
- [x] On map tap/hold update, call `MoveMowerUseCase.moveTo(sessionId, target, zone)`.
- [x] Compute and store:
	- [x] `dx = target.x - mower.x`
	- [x] `dy = target.y - mower.y`
	- [x] `distanceMm = sqrt(dx^2 + dy^2)` (rounded)
- [x] `Stop Mower` sends hold-position `MOVE_TO` to current mower position.
- [x] Clear destination marker and reset vector telemetry on stop success.

### 6) Keep map screen as monitor-only (`MapScreen.kt`)
- [x] Disable map tap navigation.
- [x] Remove navigation-only controls from Map screen.
- [x] Keep ranging/coverage visualization unaffected.

### 7) Transport parity (emulator + USB)
- [x] Ensure no emulator-only code path for target computation.
- [ ] Verify same `MOVE_TO` payload shape through both transports.
- [x] Keep command rate bounded during hold to avoid USB flooding.

## Verification Execution Plan

### Step 1: Mode behavior sanity
- [ ] Open Zone screen with Navigation OFF: confirm pan/zoom work and no command is sent on tap.
- [ ] Turn Navigation ON: confirm pan/zoom stop responding.

### Step 2: Command emission
- [ ] Single tap sends one `MOVE_TO`.
- [ ] Hold-and-drag sends repeated throttled `MOVE_TO` updates.
- [ ] Stop Mower emits hold-position command and clears destination indicator.

### Step 3: Coordinate and camera correctness
- [ ] Tap near center/corners and compare expected mm values.
- [ ] Confirm mower remains centered while map shifts under callback updates.
- [ ] Confirm OFF mode restores user-controlled pan/zoom.

### Step 4: Transport verification
- [x] Emulator: validate payloads in command log.
- [ ] USB: validate payloads in app/device logs.
- [ ] Confirm payload equivalence across emulator and USB for identical taps.

## Verification Evidence (Automated)
- [x] `:app:compileProdDebugKotlin` completed successfully (warnings only).
- [x] Emulator API smoke test:
	- Sent `MOVE_TO` with `targetXMm=1234`, `targetYMm=2345`.
	- `commandLog` last entry matched payload and destination was set to `(1234,2345)`.
	- `POST /api/v1/emulator/navigation/stop` cleared destination (`destination == null`).

## Live Verification Status Snapshot
- [x] Emulator REST endpoint reachable (`GET /api/v1/emulator/state` -> `200`).
- [x] `adb` executable detected (`C:\Users\user\AppData\Local\Android\Sdk\platform-tools\adb.exe`).
- [x] Android emulator device connected (`emulator-5554`).
- [x] USB/emulator app-log verification is runnable from this host (via absolute `adb` path).

## Manual Verification Runbook (Fast)

### 1) Zone gesture mode checks (device/emulator UI)
- Launch app and open Zone screen.
- Ensure Navigation is OFF:
	- Pan and pinch should work.
	- Taps should not send movement.
- Turn Navigation ON:
	- Pan and pinch should be blocked.
	- Tap should send one movement command.
	- Press and drag should send repeated throttled updates.
- Press Stop Mower:
	- Destination intent should clear.

### 2) App-side payload logs (USB or emulator transport)
- Capture logcat:
	- `adb logcat | findstr MoveMowerUseCase`
- Expected lines on tap/hold:
	- `MOVE_TO send attempt=... messageId=... x=... y=...`
	- `MOVE_TO accepted messageId=...`
- If transport rejects:
	- `MOVE_TO busy ...` or `MOVE_TO send failed ...`

### 3) Emulator parity check
- Keep emulator service command log visible.
- Perform one tap in Navigation mode ON.
- Compare values:
	- App log `x/y` must equal emulator command log `targetXMm/targetYMm`.

### 4) USB parity check
- Run same tap test using USB transport mode.
- Confirm app-side `MOVE_TO send ... x/y` values are produced and accepted by firmware path.
- Compare against equivalent emulator tap run to confirm payload shape/value parity.

### Step 5: Regression checks
- [ ] Zone edit mode still allows rectangle corner manipulation when navigation is OFF.
- [ ] Save/reset/clear zone flows remain functional.
- [ ] Map screen still renders coverage and ranging state without navigation actions.
