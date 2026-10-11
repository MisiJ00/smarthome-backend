# smarthome-backend

Own smart home backend: devices, rooms, users, roles and permissions, widgets. Talks to devices through MQTT (Mosquitto, Zigbee2MQTT, Shelly, ESPHome) and later Home Assistant. Web UI, app and the local AI all use the same API.

Design (data model, API, permission rules): `homeserver-infra/docs/smarthome-backend.md`.

## Stack

Java 25, Spring Boot 4, Gradle, PostgreSQL + Flyway, Spring Integration MQTT, springdoc (OpenAPI).

## Run locally

Postgres and Mosquitto come from `homeserver-infra`:

```bash
cd ../homeserver-infra
docker compose up -d mosquitto smarthome-db
```

Then start the backend from the IDE or with:

```bash
./gradlew bootRun
```

Configuration via environment variables (defaults point to localhost):
`DB_URL`, `DB_USER`, `DB_PASSWORD`, `MQTT_URL`, `MQTT_USER`, `MQTT_PASSWORD`.

API docs: http://localhost:8080/swagger-ui.html

## Tests

```bash
./gradlew test
```

Tests start their own Postgres via Testcontainers, so Docker must be running.

## Deploy

No CI/CD. On the server: `git pull`, then from `homeserver-infra` run `docker compose up -d --build smarthome-backend`.
