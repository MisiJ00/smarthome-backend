package dev.homeserver.smarthome.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * An error for the client, answered as problem detail (RFC 9457) with a German {@code title} and {@code detail} for
 * people and a stable {@code code} for programs, e.g. {@code room.name_taken}. Further properties (e.g.
 * {@code deviceCount}) can be added with {@link #with}.
 */
public class ApiException extends ResponseStatusException {

	public ApiException(HttpStatus status, String code, String detail) {
		super(status, detail);
		getBody().setTitle(Titles.of(status));
		getBody().setProperty("code", code);
	}

	public ApiException with(String property, Object value) {
		getBody().setProperty(property, value);
		return this;
	}

	public String getCode() {
		return String.valueOf(getBody().getProperties() == null ? null : getBody().getProperties().get("code"));
	}

	public static ApiException badRequest(String code, String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, code, detail);
	}

	public static ApiException forbidden(String code, String detail) {
		return new ApiException(HttpStatus.FORBIDDEN, code, detail);
	}

	public static ApiException notFound(String code, String detail) {
		return new ApiException(HttpStatus.NOT_FOUND, code, detail);
	}

	public static ApiException conflict(String code, String detail) {
		return new ApiException(HttpStatus.CONFLICT, code, detail);
	}

}
