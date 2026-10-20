package dev.homeserver.smarthome.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

import dev.homeserver.smarthome.user.CurrentUser;
import dev.homeserver.smarthome.user.Role;

/** Which client a request came from. */
public enum Via {

	@JsonProperty("web")
	WEB,

	@JsonProperty("app")
	APP,

	@JsonProperty("widget")
	WIDGET,

	@JsonProperty("ki")
	KI,

	/** The backend itself, e.g. when a confirmation expires. */
	@JsonProperty("system")
	SYSTEM;

	/** Clients name themselves in this header ({@code web}, {@code app} or {@code widget}). */
	public static final String HEADER = "X-Smarthome-Client";

	/**
	 * The AI is always {@code ki}, other machine users (scripts) {@code system}. People: the client from
	 * {@link #HEADER}, {@code app} if it is missing or unknown.
	 */
	public static Via of(CurrentUser user, String header) {
		if (user.identityId() == null) {
			return user.roles().contains(Role.KI) ? KI : SYSTEM;
		}
		if (header != null) {
			switch (header.strip().toLowerCase()) {
				case "web" -> {
					return WEB;
				}
				case "widget" -> {
					return WIDGET;
				}
				default -> {
				}
			}
		}
		return APP;
	}

	public String dbValue() {
		return name().toLowerCase();
	}

	static Via fromDb(String value) {
		return valueOf(value.toUpperCase());
	}

}
