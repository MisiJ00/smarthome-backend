package dev.homeserver.smarthome.room;

import org.springframework.http.HttpStatus;

import dev.homeserver.smarthome.error.ApiException;

/** A room that still has devices cannot be deleted. Answered with 409 and the number of devices. */
public class RoomNotEmptyException extends ApiException {

	public RoomNotEmptyException(long roomId, long deviceCount) {
		super(HttpStatus.CONFLICT, "room.not_empty", "Im Raum " + (deviceCount == 1 ? "ist noch ein Gerät" : "sind noch "
				+ deviceCount + " Geräte") + ". Bitte zuerst verschieben, dann löschen.");
		with("deviceCount", deviceCount);
	}

}
