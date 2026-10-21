package dev.homeserver.smarthome.device;

import org.springframework.http.HttpStatus;

import dev.homeserver.smarthome.error.ApiException;

/** A command was refused by a safety rule or a missing permission. Answered with 403, the reason and a code. */
public class CommandRefusedException extends ApiException {

	public CommandRefusedException(String code, String message) {
		super(HttpStatus.FORBIDDEN, code, message);
	}

}
