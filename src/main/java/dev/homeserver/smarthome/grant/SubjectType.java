package dev.homeserver.smarthome.grant;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Who a grant is for: everyone with a role, or one user. */
public enum SubjectType {

	@JsonProperty("role")
	ROLE,

	@JsonProperty("user")
	USER

}
