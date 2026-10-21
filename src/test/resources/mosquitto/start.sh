#!/bin/sh
# Legt bei jedem Start die Passwortdatei aus den Umgebungsvariablen an; Passwörter stehen nur in der .env.
# Benutzer ohne Passwort werden nicht angelegt (z. B. der Simulator auf dem Server).
set -eu
FILE=/mosquitto/data/passwd
rm -f "$FILE"
add() {
  if [ -n "$2" ]; then
    if [ -f "$FILE" ]; then
      mosquitto_passwd -b "$FILE" "$1" "$2"
    else
      mosquitto_passwd -c -b "$FILE" "$1" "$2"
    fi
  fi
}
add backend "${MQTT_BACKEND_PASSWORD:-}"
add zigbee2mqtt "${MQTT_Z2M_PASSWORD:-}"
add simulator "${MQTT_SIMULATOR_PASSWORD:-}"
if [ ! -f "$FILE" ]; then
  echo "Keine MQTT-Passwörter gesetzt (MQTT_BACKEND_PASSWORD usw. in der .env)" >&2
  exit 1
fi
chown mosquitto:mosquitto "$FILE"
chmod 0700 "$FILE"
exec /docker-entrypoint.sh /usr/sbin/mosquitto -c /mosquitto/config/mosquitto.conf
