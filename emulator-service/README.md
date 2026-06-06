# Emulator REST Service

Standalone mower emulator backend for the Android app.

The Android app no longer needs a separate emulator build flavor.
Use the prod app and switch Device Mode to EMULATOR in debug settings.

It now includes a browser UI for managing tags and visualizing mower position on a map.

## Run

```powershell
cd c:\repo\kosilka\emulator-service
node server.js
```

By default it listens on `http://localhost:8080`.

State file behavior:
- Default state file: `emulator-service/emulator-state.json`
- Override with env: `EMULATOR_STATE_FILE=<absolute-or-relative-path>`
- Service auto-loads this file on startup when it exists.

Open UI:
- `http://localhost:8080/`
- `http://localhost:8080/ui`

Coverage-test preset in UI:
- Open `http://localhost:8080/ui`
- In the **Zones** panel, click **Load 10x10 + 50 No-Go Test Preset**
- This loads the exact 10x10 available zone plus the 50 equally distributed 50 mm x 50 mm no-go squares used by `RealRangingIntegrationTest`
- Zoom in on the map to inspect the tiny no-go squares

For Android emulator, `10.0.2.2` maps to the host machine, so the app can use:
- `http://10.0.2.2:8080`

The app reads this from `BuildConfig.MOWER_SERVICE_BASE_URL` in prod build config.

## API Namespaces

### USB-compatible transport API (for app/device command flow)
Use only these endpoints for protocol-level behavior parity with USB command transport.

- `POST /api/v1/device/connect`
- `POST /api/v1/device/disconnect`
- `POST /api/v1/device/send`
- `GET /api/v1/device/messages?sinceId=<id>`

### UI-only emulator control API (for emulator browser UI)
These endpoints are for emulator UI controls and diagnostics only. They are not USB-compatible command transport.

- `GET /api/v1/ui/state`
- `POST /api/v1/ui/state/save`
- `POST /api/v1/ui/state/load`
- `GET /api/v1/ui/tags`
- `POST /api/v1/ui/tags`
- `DELETE /api/v1/ui/tags/:id`
- `PUT /api/v1/ui/tags/:id`
- `GET /api/v1/ui/zones`
- `POST /api/v1/ui/zones`
- `DELETE /api/v1/ui/zones`
- `PUT /api/v1/ui/zones/:id`
- `DELETE /api/v1/ui/zones/:id`
- `PUT /api/v1/ui/mower-position`
- `POST /api/v1/ui/scenario/activate`
- `POST /api/v1/ui/scenario/clear`
- `POST /api/v1/ui/navigation/stop`
- `PUT /api/v1/ui/settings`
- `DELETE /api/v1/ui/command-log`

Compatibility note:
- Legacy `/api/v1/emulator/*` routes are still accepted as aliases for `/api/v1/ui/*` to avoid breaking older tools.
- New integrations should use `/api/v1/ui/*` only for UI controls.

- `GET /api/v1/debug/memory`

Debug memory query params:
- `includeMessages=true|false` (default: `false`)
- `messageLimit=<1..2000>` (default: `50`, applied when `includeMessages=true`)

Examples:
- `GET /api/v1/debug/memory`
- `GET /api/v1/debug/memory?includeMessages=true`
- `GET /api/v1/debug/memory?includeMessages=true&messageLimit=200`

State persistence examples:
- `POST /api/v1/ui/state/save`
- `POST /api/v1/ui/state/load`
- `POST /api/v1/ui/state/save` with JSON body: `{ "path": "C:/tmp/mower-state.json" }`
- `POST /api/v1/ui/state/load` with JSON body: `{ "path": "C:/tmp/mower-state.json" }`

## Notes

- Messages are protocol envelopes compatible with app protocol v2.
- For command parity, use `/api/v1/device/send` + `/api/v1/device/messages` and avoid `/api/v1/ui/*` endpoints.
- `RANGING_START` starts periodic ranging samples and coverage updates.
- Scenario controls influence quality/drop/busy behavior.
- UI lets you add/remove tags, drag tags directly on the map, drag mower position on the map, and place mower location manually.
- UI lets you add/remove available and no-go zones directly on the map controls for manual parity checks while configuring zones in app.
- Use the debug memory endpoint to inspect current in-memory state and message queue for troubleshooting.
- Save/load endpoints persist and restore emulator scenario, mower position, path, and tags.
