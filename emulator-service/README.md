# Emulator REST Service

Standalone mower emulator backend for the Android app.

The Android app no longer needs a separate emulator build flavor.
Use the prod app and switch Device Mode to EMULATOR in debug settings.

## Run

```powershell
cd c:\repo\kosilka\emulator-service
node server.js
```

By default it listens on `http://localhost:8080`.

For Android emulator, `10.0.2.2` maps to the host machine, so the app can use:
- `http://10.0.2.2:8080`

The app reads this from `BuildConfig.EMULATOR_BASE_URL` in prod flavor.

## Endpoints

- `POST /api/v1/device/connect`
- `POST /api/v1/device/disconnect`
- `POST /api/v1/device/send`
- `GET /api/v1/device/messages?sinceId=<id>`
- `GET /api/v1/emulator/state`
- `POST /api/v1/emulator/scenario/activate`
- `POST /api/v1/emulator/scenario/clear`

## Notes

- Messages are protocol envelopes compatible with app protocol v2.
- `RANGING_START` starts periodic ranging samples and coverage updates.
- Scenario controls influence quality/drop/busy behavior.
