package dev.homeserver.smarthome.user;

import org.springframework.security.authentication.DisabledException;

public class UserDisabledException extends DisabledException {

	public UserDisabledException(User user) {
		super("User " + user.getId() + " is disabled");
	}

}
