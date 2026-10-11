package dev.homeserver.smarthome.device;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A command was refused by a safety rule. Answered with 403 and the reason. */
public class CommandRefusedException extends ResponseStatusException {

	public CommandRefusedException(String message) {
		super(HttpStatus.FORBIDDEN, message);
	}

}
