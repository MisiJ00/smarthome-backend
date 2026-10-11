package dev.homeserver.smarthome.device;

import java.util.Map;

/** Published by adapters whenever a device reports its state. */
public record DeviceStateChanged(String adapter, String externalId, Map<String, Object> state) {
}
