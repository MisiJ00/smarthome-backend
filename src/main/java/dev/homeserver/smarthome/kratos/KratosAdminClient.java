package dev.homeserver.smarthome.kratos;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Talks to the Kratos admin API: creates, changes and deletes identities and hands out setup codes. Errors from Kratos
 * become problem details: 409 when email or username are taken, 400 for invalid traits, 503 when Kratos is not
 * reachable.
 */
@Component
public class KratosAdminClient {

	private static final String SCHEMA = "person";

	private final RestClient http;
	private final KratosProperties properties;

	public KratosAdminClient(KratosProperties properties) {
		this.properties = properties;
		this.http = RestClient.builder().baseUrl(properties.adminUrl() == null ? "" : properties.adminUrl()).build();
	}

	public record Traits(String email, String username, String name) {
	}

	/** A one-time code for a new (or locked out) account. The person enters it and then sets a password. */
	public record SetupCode(String code, String link, Instant expiresAt) {
	}

	/** Creates an active identity without a password. */
	public UUID createIdentity(Traits traits) {
		IdentityResponse identity = call(() -> http.post().uri("/admin/identities")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("schema_id", SCHEMA, "traits", traits))
				.retrieve().body(IdentityResponse.class));
		return identity.id();
	}

	public SetupCode createSetupCode(UUID identityId) {
		RecoveryCodeResponse response = call(() -> http.post().uri("/admin/recovery/code")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("identity_id", identityId, "expires_in", properties.setupCodeLifetime().toSeconds() + "s"))
				.retrieve().body(RecoveryCodeResponse.class));
		return new SetupCode(response.recoveryCode(), response.recoveryLink(), response.expiresAt());
	}

	public void rename(UUID identityId, String name) {
		patch(identityId, List.of(Map.of("op", "replace", "path", "/traits/name", "value", name)));
	}

	/** Inactive identities cannot sign in; deactivating also ends all their sessions. */
	public void setActive(UUID identityId, boolean active) {
		patch(identityId, List.of(Map.of("op", "replace", "path", "/state", "value", active ? "active" : "inactive")));
		if (!active) {
			call(() -> http.delete().uri("/admin/identities/{id}/sessions", identityId).retrieve()
					.onStatus(status -> status.value() == 404, (request, response) -> {
						// No sessions
					})
					.toBodilessEntity());
		}
	}

	/** Deletes the identity; an identity that is already gone counts as deleted. */
	public void deleteIdentity(UUID identityId) {
		call(() -> http.delete().uri("/admin/identities/{id}", identityId).retrieve()
				.onStatus(status -> status.value() == 404, (request, response) -> {
					// Already deleted
				})
				.toBodilessEntity());
	}

	private void patch(UUID identityId, List<Map<String, Object>> operations) {
		call(() -> http.patch().uri("/admin/identities/{id}", identityId)
				.contentType(MediaType.APPLICATION_JSON)
				.body(operations)
				.retrieve().toBodilessEntity());
	}

	private <T> T call(Supplier<T> request) {
		if (properties.adminUrl() == null || properties.adminUrl().isBlank()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kratos admin API is not configured");
		}
		try {
			return request.get();
		}
		catch (RestClientResponseException e) {
			throw translate(e.getStatusCode(), e.getResponseBodyAsString());
		}
		catch (ResourceAccessException e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kratos is not reachable");
		}
	}

	private static ResponseStatusException translate(HttpStatusCode status, String body) {
		return switch (status.value()) {
			case 409 -> new ResponseStatusException(HttpStatus.CONFLICT, "Email or username is already taken");
			case 400 -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kratos rejected the account: " + body);
			case 404 -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Identity not found in Kratos");
			default -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Kratos answered " + status.value());
		};
	}

	record IdentityResponse(UUID id) {
	}

	record RecoveryCodeResponse(@JsonProperty("recovery_code") String recoveryCode,
			@JsonProperty("recovery_link") String recoveryLink, @JsonProperty("expires_at") Instant expiresAt) {
	}

}
