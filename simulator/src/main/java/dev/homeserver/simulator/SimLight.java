package dev.homeserver.simulator;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Dimmable white-spectrum bulb. */
class SimLight extends SimDevice {

	SimLight(String ieeeAddress, String friendlyName) {
		super(ieeeAddress, friendlyName);
		state.put("state", "OFF");
		state.put("brightness", 254);
		state.put("color_temp", 370);
		state.put("linkquality", 120);
	}

	@Override
	String vendor() {
		return "IKEA";
	}

	@Override
	String model() {
		return "LED2201G8";
	}

	@Override
	String description() {
		return "TRADFRI bulb E27, white spectrum (simulated)";
	}

	@Override
	List<Map<String, Object>> exposes() {
		return List.of(
				composite("light", List.of(
						binary("state", ALL, "ON", "OFF"),
						numeric("brightness", ALL, 0, 254, null),
						numeric("color_temp", ALL, 250, 454, "mired"))),
				numeric("linkquality", PUBLISHED, 0, 255, "lqi"));
	}

	@Override
	boolean apply(Map<String, Object> command) {
		var before = Map.copyOf(state);
		if (command.containsKey("state")) {
			state.put("state", onOff(command.get("state"), state.get("state"), "ON", "OFF"));
		}
		if (command.containsKey("brightness")) {
			var brightness = (int) clamp(number(command.get("brightness"), 0), 0, 254);
			if (brightness == 0) {
				state.put("state", "OFF");
			}
			else {
				state.put("brightness", brightness);
				state.put("state", "ON");
			}
		}
		if (command.containsKey("color_temp")) {
			state.put("color_temp", (int) clamp(number(command.get("color_temp"), 370), 250, 454));
		}
		return !Objects.equals(before, state);
	}

}
