package dev.homeserver.simulator;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Smart plug with power metering. Stands in for the plug that measures the server
 * (a Shelly in the real setup, which speaks its own MQTT format).
 */
class SimPlug extends SimDevice {

	private final int tickSeconds;

	SimPlug(String ieeeAddress, String friendlyName, int tickSeconds) {
		super(ieeeAddress, friendlyName);
		this.tickSeconds = tickSeconds;
		state.put("state", "ON");
		state.put("power", 112.0);
		state.put("energy", 12.345);
		state.put("voltage", 231.0);
		state.put("current", 0.49);
		state.put("linkquality", 140);
	}

	@Override
	String vendor() {
		return "Simulator";
	}

	@Override
	String model() {
		return "SIM-PLUG";
	}

	@Override
	String description() {
		return "Smart plug with power metering (simulated)";
	}

	@Override
	List<Map<String, Object>> exposes() {
		return List.of(
				composite("switch", List.of(binary("state", ALL, "ON", "OFF"))),
				numeric("power", PUBLISHED | GET, null, null, "W"),
				numeric("energy", PUBLISHED | GET, null, null, "kWh"),
				numeric("voltage", PUBLISHED | GET, null, null, "V"),
				numeric("current", PUBLISHED | GET, null, null, "A"),
				numeric("linkquality", PUBLISHED, 0, 255, "lqi"));
	}

	@Override
	boolean apply(Map<String, Object> command) {
		var before = Map.copyOf(state);
		if (command.containsKey("state")) {
			state.put("state", onOff(command.get("state"), state.get("state"), "ON", "OFF"));
			updatePower();
		}
		return !Objects.equals(before, state);
	}

	@Override
	boolean tick() {
		updatePower();
		var energy = number(state.get("energy"), 0) + number(state.get("power"), 0) * tickSeconds / 3_600_000;
		state.put("energy", Math.round(energy * 1000) / 1000.0);
		return true;
	}

	private void updatePower() {
		var random = ThreadLocalRandom.current();
		var power = "ON".equals(state.get("state")) ? 90 + random.nextDouble(50) : 0;
		var voltage = 228 + random.nextDouble(5);
		state.put("power", Math.round(power * 10) / 10.0);
		state.put("voltage", Math.round(voltage * 10) / 10.0);
		state.put("current", Math.round(power / voltage * 100) / 100.0);
	}

}
