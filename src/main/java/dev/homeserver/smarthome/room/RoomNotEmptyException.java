package dev.homeserver.smarthome.room;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A room that still has devices cannot be deleted. Answered with 409 and the number of devices. */
public class RoomNotEmptyException extends ResponseStatusException {

	public RoomNotEmptyException(long roomId, long deviceCount) {
		super(HttpStatus.CONFLICT, "Room " + roomId + " still has " + deviceCount + " device(s), move them out first");
		getBody().setProperty("deviceCount", deviceCount);
	}

}
