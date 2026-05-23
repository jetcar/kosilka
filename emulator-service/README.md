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

Open UI:
- `http://localhost:8080/`
- `http://localhost:8080/ui`

For Android emulator, `10.0.2.2` maps to the host machine, so the app can use:
- `http://10.0.2.2:8080`

The app reads this from `BuildConfig.MOWER_SERVICE_BASE_URL` in prod build config.

## Endpoints

- `POST /api/v1/device/connect`
- `POST /api/v1/device/disconnect`
- `POST /api/v1/device/send`
- `GET /api/v1/device/messages?sinceId=<id>`
- `GET /api/v1/emulator/state`
- `GET /api/v1/emulator/tags`
- `POST /api/v1/emulator/tags`
- `DELETE /api/v1/emulator/tags/:id`
- `PUT /api/v1/emulator/tags/:id`
- `PUT /api/v1/emulator/mower-position`
- `POST /api/v1/emulator/scenario/activate`
- `POST /api/v1/emulator/scenario/clear`
- `GET /api/v1/debug/memory`

Debug memory query params:
- `includeMessages=true|false` (default: `false`)
- `messageLimit=<1..2000>` (default: `50`, applied when `includeMessages=true`)

Examples:
- `GET /api/v1/debug/memory`
- `GET /api/v1/debug/memory?includeMessages=true`
- `GET /api/v1/debug/memory?includeMessages=true&messageLimit=200`

## Notes

- Messages are protocol envelopes compatible with app protocol v2.
- `RANGING_START` starts periodic ranging samples and coverage updates.
- Scenario controls influence quality/drop/busy behavior.
- UI lets you add/remove tags, drag tags directly on the map, drag mower position on the map, and place mower location manually.
- Use the debug memory endpoint to inspect current in-memory state and message queue for troubleshooting.
