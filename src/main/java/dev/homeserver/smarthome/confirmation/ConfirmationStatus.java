package dev.homeserver.smarthome.confirmation;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ConfirmationStatus {

	@JsonProperty("pending")
	PENDING,

	@JsonProperty("approved")
	APPROVED,

	@JsonProperty("rejected")
	REJECTED,

	@JsonProperty("expired")
	EXPIRED;

	public String dbValue() {
		return name().toLowerCase();
	}

	static ConfirmationStatus fromDb(String value) {
		return valueOf(value.toUpperCase());
	}

}
