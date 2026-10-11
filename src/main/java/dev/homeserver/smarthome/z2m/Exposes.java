package dev.homeserver.smarthome.z2m;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Helpers for reading Zigbee2MQTT "exposes" (the capability description of a device). */
final class Exposes {

	private static final int ACCESS_GET = 4;

	private Exposes() {
	}

	/** Derives our device type from the top-level exposes. */
	static String deviceType(List<Map<String, Object>> exposes) {
		var types = exposes.stream().map(e -> String.valueOf(e.get("type"))).toList();
		if (types.contains("lock")) {
			return "lock";
		}
		if (types.contains("climate")) {
			return "thermostat";
		}
		if (types.contains("light")) {
			return "light";
		}
		if (types.contains("switch")) {
			return "switch";
		}
		return "sensor";
	}

	/** Builds a {@code /get} payload asking for every property the device can be queried for. */
	static Map<String, Object> getRequest(List<Map<String, Object>> exposes) {
		var request = new LinkedHashMap<String, Object>();
		features(exposes)
				.filter(f -> f.get("access") instanceof Number access && (access.intValue() & ACCESS_GET) != 0)
				.forEach(f -> request.put(String.valueOf(f.get("property")), ""));
		return request;
	}

	@SuppressWarnings("unchecked")
	private static Stream<Map<String, Object>> features(List<Map<String, Object>> exposes) {
		return exposes.stream().flatMap(e -> e.get("features") instanceof List<?> nested
				? features((List<Map<String, Object>>) nested)
				: Stream.of(e));
	}

}
