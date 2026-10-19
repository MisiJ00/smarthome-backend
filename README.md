# smarthome-backend

Own smart home backend: devices, rooms, users, roles and permissions, widgets. Talks to devices through MQTT (Mosquitto, Zigbee2MQTT, Shelly, ESPHome) and later Home Assistant. Web UI, app and the local AI all use the same API.

Design (data model, API, permission rules): `homeserver-infra/docs/smarthome-backend.md`.

## Stack

Java 25, Spring Boot 4, Gradle, PostgreSQL + Flyway, Spring Integration MQTT, springdoc (OpenAPI).

## Run locally

Postgres, Mosquitto and Kratos (login) come from `homeserver-infra`:

```bash
cd ../homeserver-infra
node auth/generate-jwks.mjs        # once: signing keys for the Kratos JWTs
docker compose up -d mosquitto smarthome-db kratos
```

Then start the backend from the IDE (`SmarthomeBackendApplication`) or with:

```bash
./gradlew bootRun
```

Configuration via environment variables (defaults point to localhost):
`DB_URL`, `DB_USER`, `DB_PASSWORD`, `MQTT_URL`, `MQTT_USER`, `MQTT_PASSWORD`,
`AUTH_JWKS` (public keys of the Kratos tokenizer, default `file:../homeserver-infra/auth/kratos/secrets/jwks.public.json`),
`AUTH_ISSUER` (Kratos public URL, default `http://localhost:4433/`),
`AUTH_INITIAL_ADMIN` (Kratos identity id or email of the first admin).

API docs: http://localhost:8080/swagger-ui.html (use "Authorize" with a JWT, see below).

## Login

The backend has no login of its own. Apps sign in at Ory Kratos and send a short-lived JWT:

1. Login via the Kratos API flow (`/self-service/login/api`) → session token
2. `GET /sessions/whoami?tokenize_as=smarthome` with `X-Session-Token` → `tokenized` (JWT, ES256, 5 minutes)
3. `Authorization: Bearer <JWT>` on every request here

The backend checks the signature with the public key, then looks up the user by the Kratos identity id (`sub`) and creates it on the first request. Roles live here, not in Kratos. The person configured in `AUTH_INITIAL_ADMIN` always gets the `admin` role. `GET /api/me` shows who you are. Until grants exist, the device API is admin only. Full curl walkthrough: `homeserver-infra/auth/README.md`.

## Simulator

Without hardware, the simulator pretends to be Zigbee2MQTT on the same Mosquitto: a lamp, a radiator thermostat, a power-metering plug (stands in for the server plug) and a door lock. Start it in a second terminal:

```bash
./gradlew :simulator:run
```

It uses the same environment variables `MQTT_URL`, `MQTT_USER`, `MQTT_PASSWORD`, plus `Z2M_BASE_TOPIC` (default `zigbee2mqtt`).

## Devices API

| Call | What it does |
| --- | --- |
| `GET /api/devices/discovered` | Devices the adapters see that have not been added yet |
| `POST /api/devices` | Add one: `{"adapter": "z2m", "externalId": "0x...", "name": "Deckenlampe", "roomId": null}` |
| `GET /api/devices`, `GET /api/devices/{id}` | Added devices with their current state |
| `PATCH /api/devices/{id}` | Change `name`, `roomId`, `lockedActions` |
| `DELETE /api/devices/{id}` | Remove a device again |
| `POST /api/devices/{id}/commands` | Send a command, e.g. `{"state": "ON", "brightness": 120}` |

Safety rules (`CommandGuard`): an action listed in `lockedActions` is always refused, e.g. `["switch_off"]` on the server plug (a toggle counts as switching off too). Unlocking is refused until the confirmation flow exists.

## Tests

```bash
./gradlew test
```

Tests start their own Postgres via Testcontainers, so Docker must be running.

## Deploy

No CI/CD. On the server: `git pull`, then from `homeserver-infra` run `docker compose up -d --build smarthome-backend`.
