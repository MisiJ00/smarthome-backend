package dev.homeserver.smarthome.device;

import java.util.Map;

/** A device an adapter can see, whether or not it has been added yet. */
public record DiscoveredDevice(
		String adapter,
		String externalId,
		String suggestedName,
		String type,
		String vendor,
		String model,
		String description,
		Map<String, Object> capabilities,
		Map<String, Object> state) {
}
