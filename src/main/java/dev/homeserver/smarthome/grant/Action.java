package dev.homeserver.smarthome.grant;

import com.fasterxml.jackson.annotation.JsonProperty;

/** What a grant allows, ascending. Any action also allows {@link #VIEW}; {@link #UNLOCK} always needs a confirmation. */
public enum Action {

	@JsonProperty("view")
	VIEW,

	/** Switch, dim, set temperature. */
	@JsonProperty("control")
	CONTROL,

	/** Rename, move to another room. */
	@JsonProperty("configure")
	CONFIGURE,

	/** Only for locks. */
	@JsonProperty("unlock")
	UNLOCK;

	public String dbValue() {
		return name().toLowerCase();
	}

	static Action fromDb(String value) {
		return valueOf(value.toUpperCase());
	}

}
