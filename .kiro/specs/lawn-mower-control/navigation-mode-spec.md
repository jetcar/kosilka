# Zone Navigation and Editing Spec

## Goal
Provide safe, deterministic mower navigation while keeping zone editing flexible and predictable.

## Current Behavior

### Navigation mode OFF
- Map supports pan and zoom (pinch + `+/-` controls).
- Tap does not send `MOVE_TO`.
- Edit mode allows zone corner drag, zone drag, and empty-area map pan.
- Edit mode must not send movement commands.

### Navigation mode ON
- Pan and zoom gestures are disabled.
- Tap sends one absolute `MOVE_TO` (`targetXMm`, `targetYMm`).
- Tap-and-hold sends throttled updates.
- Mower is rendered at viewport center while map content moves under it.

### Edit mode safety
- Movement dispatch is disabled while editing.
- Misclick/drag in edit mode cannot issue navigation commands.

## Zone Model Rules
- Zones are polygonal (not rectangle-locked).
- Corner dragging updates individual vertices directly.
- Persisted zones must keep authored polygon shape; no rectangle re-normalization on reload.
- No-Go zones can be empty.
- Available zone must keep at least one zone.

## Coordinate and Safety Rules
- Coordinate system is millimeters in world space.
- App and emulator Y-axis are aligned (screen up maps to world up).
- Editing plane is unbounded (no hardcoded map-bounds clamp for editing).
- Navigation safety is enforced by zone constraints (available/no-go), not hardcoded coordinate limits.

## Command Rules
- Tap: one `MOVE_TO`.
- Hold: throttled `MOVE_TO` stream (latest pointer wins).
- Stop: sends hold-position command and clears destination marker.
- Zone save is local-only in the app; it does not send zone create/update/delete commands to emulator/device.
- Emulator/device control from Zone screen is limited to mower movement commands.

## Verification Checklist

### Navigation and safety
- [x] Navigation OFF: tap does not send `MOVE_TO`.
- [x] Navigation ON: tap sends one `MOVE_TO`.
- [x] Navigation ON: hold sends throttled updates.
- [x] Edit mode: no drag/tap emits movement commands.

### Editing behavior
- [x] Drag corner to arbitrary polygon shape; shape remains after drag end.
- [x] Delete last No-Go zone succeeds.
- [x] Last available zone cannot be deleted.
- [x] Empty-area drag in edit mode pans map.

### Coordinate parity
- [x] Tapping up in app moves mower up in emulator/world.
- [x] App `targetXMm/targetYMm` matches emulator command log values.

### Bounds and constraints
- [x] Zooming out does not shrink editable area.
- [x] Zone editing works outside prior fixed map rectangle.
- [x] Movement clamping behavior is driven by available/no-go zones only.

## Acceptance Criteria
- Navigation and editing are mode-safe and deterministic.
- No accidental movement in edit mode.
- Zone shapes are free-form and stable after persistence.
- Coordinate behavior matches emulator/world semantics.
