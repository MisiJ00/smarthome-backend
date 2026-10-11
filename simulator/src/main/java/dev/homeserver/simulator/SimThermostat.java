package dev.homeserver.simulator;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Radiator thermostat. The room slowly warms up while heating and cools down otherwise. */
class SimThermostat extends SimDevice {

	private static final double ROOM_BASELINE = 17.0;

	SimThermostat(String ieeeAddress, String friendlyName) {
		super(ieeeAddress, friendlyName);
		state.put("occupied_heating_setpoint", 21.0);
		state.put("local_temperature", 19.5);
		state.put("system_mode", "heat");
		state.put("running_state", "heat");
		state.put("battery", 87);
		state.put("linkquality", 96);
	}

	@Override
	String vendor() {
		return "SONOFF";
	}

	@Override
	String model() {
		return "TRVZB";
	}

	@Override
	String description() {
		return "Zigbee thermostatic radiator valve (simulated)";
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
		var setpoint = numeric("occupied_heating_setpoint", ALL, 4, 35, "°C");
		setpoint.put("value_step", 0.5);
		return List.of(
				composite("climate", List.of(
						setpoint,
						numeric("local_temperature", PUBLISHED | GET, null, null, "°C"),
						enumeration("system_mode", ALL, List.of("off", "auto", "heat")),
						enumeration("running_state", PUBLISHED | GET, List.of("idle", "heat")))),
				numeric("battery", PUBLISHED, 0, 100, "%"),
				numeric("linkquality", PUBLISHED, 0, 255, "lqi"));
	}

	@Override
	boolean apply(Map<String, Object> command) {
		var before = Map.copyOf(state);
		if (command.containsKey("occupied_heating_setpoint")) {
			var value = number(command.get("occupied_heating_setpoint"), setpoint());
			state.put("occupied_heating_setpoint", Math.round(clamp(value, 4, 35) * 2) / 2.0);
		}
		if (command.get("system_mode") instanceof String mode && List.of("off", "auto", "heat").contains(mode)) {
			state.put("system_mode", mode);
		}
		updateRunningState();
		return !Objects.equals(before, state);
	}

	@Override
	boolean tick() {
		var before = Map.copyOf(state);
		var temperature = number(state.get("local_temperature"), ROOM_BASELINE);
		temperature += heating() ? 0.1 : (temperature > ROOM_BASELINE ? -0.05 : 0);
		state.put("local_temperature", Math.round(temperature * 10) / 10.0);
		updateRunningState();
		return !Objects.equals(before, state);
	}

	private void updateRunningState() {
		state.put("running_state", heating() ? "heat" : "idle");
	}

	private boolean heating() {
		return !"off".equals(state.get("system_mode"))
				&& number(state.get("local_temperature"), 0) < setpoint() - 0.2;
	}

	private double setpoint() {
		return number(state.get("occupied_heating_setpoint"), 21);
	}

}
