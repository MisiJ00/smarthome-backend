package dev.homeserver.simulator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A simulated device. Describes itself like Zigbee2MQTT does in {@code bridge/devices}
 * and keeps a state that reacts to {@code /set} commands.
 */
abstract class SimDevice {

	// Access bits as used by Zigbee2MQTT exposes
	static final int PUBLISHED = 1;
	static final int SET = 2;
	static final int GET = 4;
	static final int ALL = PUBLISHED | SET | GET;

	final String ieeeAddress;
	final String friendlyName;
	final Map<String, Object> state = new LinkedHashMap<>();

	SimDevice(String ieeeAddress, String friendlyName) {
		this.ieeeAddress = ieeeAddress;
		this.friendlyName = friendlyName;
	}

	abstract String vendor();

	abstract String model();

	abstract String description();

	abstract List<Map<String, Object>> exposes();

	/** Applies a {@code /set} payload. Returns true if the state changed. */
	abstract boolean apply(Map<String, Object> command);

	/** Called periodically to simulate sensor values. Returns true if the state changed. */
	boolean tick() {
		return false;
	}

	String powerSource() {
		return "Mains (single phase)";
	}

	String zigbeeType() {
		return "Router";
	}

	Map<String, Object> bridgeInfo() {
		var definition = new LinkedHashMap<String, Object>();
		definition.put("vendor", vendor());
		definition.put("model", model());
		definition.put("description", description());
		definition.put("exposes", exposes());
		definition.put("options", List.of());

		var info = new LinkedHashMap<String, Object>();
		info.put("ieee_address", ieeeAddress);
		info.put("type", zigbeeType());
		info.put("network_address", Math.abs(ieeeAddress.hashCode()) % 65000);
		info.put("friendly_name", friendlyName);
		info.put("supported", true);
		info.put("disabled", false);
		info.put("interview_completed", true);
		info.put("interviewing", false);
		info.put("power_source", powerSource());
		info.put("definition", definition);
		return info;
	}

	// Helpers for building exposes

	static Map<String, Object> binary(String name, int access, Object on, Object off) {
		var m = feature("binary", name, access);
		m.put("value_on", on);
		m.put("value_off", off);
		m.put("value_toggle", "TOGGLE");
		return m;
	}

	static Map<String, Object> numeric(String name, int access, Number min, Number max, String unit) {
		var m = feature("numeric", name, access);
		if (min != null) {
			m.put("value_min", min);
		}
		if (max != null) {
			m.put("value_max", max);
		}
		if (unit != null) {
			m.put("unit", unit);
		}
		return m;
	}

	static Map<String, Object> enumeration(String name, int access, List<String> values) {
		var m = feature("enum", name, access);
		m.put("values", values);
		return m;
	}

	static Map<String, Object> composite(String type, List<Map<String, Object>> features) {
		var m = new LinkedHashMap<String, Object>();
		m.put("type", type);
		m.put("features", features);
		return m;
	}

	private static Map<String, Object> feature(String type, String name, int access) {
		var m = new LinkedHashMap<String, Object>();
		m.put("type", type);
		m.put("name", name);
		m.put("property", name);
		m.put("access", access);
		return m;
	}

	static double number(Object value, double fallback) {
		if (value instanceof Number n) {
			return n.doubleValue();
		}
		if (value instanceof String s) {
			try {
				return Double.parseDouble(s);
			}
			catch (NumberFormatException e) {
				return fallback;
			}
		}
		return fallback;
	}

	static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	/** Resolves ON/OFF/TOGGLE (case-insensitive) against the current value. */
	static String onOff(Object value, Object current, String on, String off) {
		var v = String.valueOf(value).toUpperCase();
		if (v.equals("TOGGLE")) {
			return on.equals(current) ? off : on;
		}
		return v.equals(on) ? on : v.equals(off) ? off : String.valueOf(current);
	}

}
