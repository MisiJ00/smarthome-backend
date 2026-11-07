package dev.homeserver.smarthome.widget;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum TargetType {

	@JsonProperty("device")
	DEVICE,

	@JsonProperty("room")
	ROOM,

	/** Reserved in the data model; there are no scenes yet. */
	@JsonProperty("scene")
	SCENE;

	public String dbValue() {
		return name().toLowerCase();
	}

}
