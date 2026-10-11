package dev.homeserver.simulator;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Door lock. Stands in for the Nuki, which comes last in the real setup. */
class SimLock extends SimDevice {

	SimLock(String ieeeAddress, String friendlyName) {
		super(ieeeAddress, friendlyName);
		state.put("state", "LOCK");
		state.put("lock_state", "locked");
		state.put("battery", 64);
		state.put("linkquality", 88);
	}

	@Override
	String vendor() {
		return "Simulator";
	}

	@Override
	String model() {
		return "SIM-LOCK";
	}

	@Override
	String description() {
		return "Door lock (simulated)";
	}

	@Override
	String powerSource() {
		return "Battery";
	}

	@Override
	String zigbeeType() {
		return "EndDevice";
	}

	@Override
	List<Map<String, Object>> exposes() {
		var state = binary("state", ALL, "LOCK", "UNLOCK");
		state.remove("value_toggle");
		return List.of(
				composite("lock", List.of(
						state,
						enumeration("lock_state", PUBLISHED | GET, List.of("not_fully_locked", "locked", "unlocked")))),
				numeric("battery", PUBLISHED, 0, 100, "%"),
				numeric("linkquality", PUBLISHED, 0, 255, "lqi"));
	}

	@Override
	boolean apply(Map<String, Object> command) {
		var before = Map.copyOf(state);
		var value = String.valueOf(command.get("state")).toUpperCase();
		if (value.equals("LOCK") || value.equals("UNLOCK")) {
			state.put("state", value);
			state.put("lock_state", value.equals("LOCK") ? "locked" : "unlocked");
		}
		return !Objects.equals(before, state);
	}

}
