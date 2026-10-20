package dev.homeserver.smarthome.grant;

import com.fasterxml.jackson.annotation.JsonProperty;

/** What a grant applies to: every device, the devices in one room, or one device. */
public enum ScopeType {

	@JsonProperty("all")
	ALL,

	@JsonProperty("room")
	ROOM,

	@JsonProperty("device")
	DEVICE;

	public String dbValue() {
		return name().toLowerCase();
	}

	static ScopeType fromDb(String value) {
		return valueOf(value.toUpperCase());
	}

}
