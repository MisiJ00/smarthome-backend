package dev.homeserver.smarthome.widget;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What a widget shows and offers. Device widgets fit certain device types; {@code state} lists the state values the
 * homescreen data contains (empty: all of them).
 */
public enum WidgetKind {

	/** On/off for lights and switches. */
	@JsonProperty("switch")
	SWITCH(TargetType.DEVICE, Set.of("light", "switch"), List.of("state")),

	@JsonProperty("dimmer")
	DIMMER(TargetType.DEVICE, Set.of("light"), List.of("state", "brightness")),

	@JsonProperty("thermostat")
	THERMOSTAT(TargetType.DEVICE, Set.of("thermostat"),
			List.of("local_temperature", "current_heating_setpoint", "system_mode", "battery")),

	@JsonProperty("lock")
	LOCK(TargetType.DEVICE, Set.of("lock"), List.of("state", "lock_state", "battery")),

	/** Any device, all of its state (e.g. the server plug's power). */
	@JsonProperty("status")
	STATUS(TargetType.DEVICE, Set.of(), List.of()),

	/** A room: its devices at a glance. */
	@JsonProperty("room")
	ROOM(TargetType.ROOM, Set.of(), List.of());

	private final TargetType target;
	private final Set<String> deviceTypes;
	private final List<String> state;

	WidgetKind(TargetType target, Set<String> deviceTypes, List<String> state) {
		this.target = target;
		this.deviceTypes = deviceTypes;
		this.state = state;
	}

	public TargetType target() {
		return target;
	}

	/** Whether this kind can show a device of that type; empty means every type. */
	public boolean fits(String deviceType) {
		return deviceTypes.isEmpty() || deviceTypes.contains(deviceType);
	}

	public List<String> state() {
		return state;
	}

	public String dbValue() {
		return name().toLowerCase();
	}

}
