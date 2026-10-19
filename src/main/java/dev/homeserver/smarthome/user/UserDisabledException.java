package dev.homeserver.smarthome.user;

import java.util.UUID;

import org.springframework.security.authentication.DisabledException;

public class UserDisabledException extends DisabledException {

	public UserDisabledException(UUID identityId) {
		super("User for identity " + identityId + " is disabled");
	}

}
