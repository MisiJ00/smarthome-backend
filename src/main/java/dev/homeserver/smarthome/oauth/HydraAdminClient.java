package dev.homeserver.smarthome.oauth;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonProperty;

import dev.homeserver.smarthome.error.ApiException;

/**
 * Talks to the Hydra admin API, which only the backend can reach: reads and answers the login, consent and logout
 * requests of OAuth2 clients. Hydra answers each decision with the address the browser has to go to next. A challenge
 * Hydra does not know is a 400 here, one that was used already or has expired a 409, an unreachable Hydra a 503.
 */
@Component
public class HydraAdminClient {

	private final RestClient http;
	private final HydraProperties properties;

	public HydraAdminClient(HydraProperties properties) {
		this.properties = properties;
		this.http = RestClient.builder().baseUrl(properties.adminUrl() == null ? "" : properties.adminUrl()).build();
	}

	public record Client(@JsonProperty("client_id") String clientId, @JsonProperty("client_name") String clientName,
			Map<String, Object> metadata) {

		/** Own services marked as trusted skip the consent page; only the admin API can set this (client metadata). */
		public boolean trusted() {
			return metadata != null && Boolean.TRUE.equals(metadata.get("trusted"));
		}

		public String displayName() {
			return clientName != null && !clientName.isBlank() ? clientName : clientId;
		}
	}

	public record LoginRequest(boolean skip, String subject, Client client,
			@JsonProperty("requested_scope") List<String> requestedScope) {
	}

	public record ConsentRequest(boolean skip, String subject, Client client,
			@JsonProperty("requested_scope") List<String> requestedScope,
			@JsonProperty("requested_access_token_audience") List<String> requestedAudience) {
	}

	public record LogoutRequest(String subject, @JsonProperty("rp_initiated") boolean rpInitiated, Client client) {
	}

	record Redirect(@JsonProperty("redirect_to") String redirectTo) {
	}

	public LoginRequest loginRequest(String challenge) {
		return call(() -> http.get().uri("/admin/oauth2/auth/requests/login?login_challenge={c}", challenge)
				.retrieve().body(LoginRequest.class));
	}

	/** Hydra remembers nothing ({@code remember: false}): every login asks Kratos's session again. */
	public String acceptLogin(String challenge, String subject, String acr) {
		Map<String, Object> body = acr == null ? Map.of("subject", subject, "remember", false)
				: Map.of("subject", subject, "remember", false, "acr", acr);
		return put("/admin/oauth2/auth/requests/login/accept?login_challenge={c}", challenge, body);
	}

	public String rejectLogin(String challenge, String error, String description) {
		return put("/admin/oauth2/auth/requests/login/reject?login_challenge={c}", challenge,
				Map.of("error", error, "error_description", description));
	}

	public ConsentRequest consentRequest(String challenge) {
		return call(() -> http.get().uri("/admin/oauth2/auth/requests/consent?consent_challenge={c}", challenge)
				.retrieve().body(ConsentRequest.class));
	}

	public String acceptConsent(String challenge, List<String> grantScope, List<String> grantAudience,
			Map<String, Object> idTokenClaims) {
		return put("/admin/oauth2/auth/requests/consent/accept?consent_challenge={c}", challenge,
				Map.of("grant_scope", grantScope, "grant_access_token_audience", grantAudience, "remember", false,
						"session", Map.of("id_token", idTokenClaims)));
	}

	public String rejectConsent(String challenge, String error, String description) {
		return put("/admin/oauth2/auth/requests/consent/reject?consent_challenge={c}", challenge,
				Map.of("error", error, "error_description", description));
	}

	public LogoutRequest logoutRequest(String challenge) {
		return call(() -> http.get().uri("/admin/oauth2/auth/requests/logout?logout_challenge={c}", challenge)
				.retrieve().body(LogoutRequest.class));
	}

	public String acceptLogout(String challenge) {
		return put("/admin/oauth2/auth/requests/logout/accept?logout_challenge={c}", challenge, null);
	}

	/** Hydra answers 204: the person stays signed in and the service has to cope; there is nowhere to send them. */
	public void rejectLogout(String challenge) {
		call(() -> http.put().uri("/admin/oauth2/auth/requests/logout/reject?logout_challenge={c}", challenge)
				.retrieve().toBodilessEntity());
	}

	private String put(String uri, String challenge, Map<String, Object> body) {
		Redirect redirect = call(() -> {
			var request = http.put().uri(uri, challenge).contentType(MediaType.APPLICATION_JSON);
			return (body == null ? request : request.body(body)).retrieve().body(Redirect.class);
		});
		if (redirect == null || redirect.redirectTo() == null) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "oauth.error", "Hydra hat keine Weiterleitung geschickt.");
		}
		return redirect.redirectTo();
	}

	private <T> T call(Supplier<T> request) {
		if (properties.adminUrl() == null || properties.adminUrl().isBlank()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "oauth.not_configured",
					"Die Anmeldung für andere Dienste (Hydra) ist nicht eingerichtet.");
		}
		try {
			return request.get();
		}
		catch (RestClientResponseException e) {
			throw switch (e.getStatusCode().value()) {
				case 404 -> ApiException.badRequest("oauth.challenge_unknown",
						"Diese Anmeldeanfrage gibt es nicht oder sie ist abgelaufen. Bitte im Dienst noch einmal auf "
								+ "\"Anmelden\" tippen.");
				case 409, 410 -> ApiException.conflict("oauth.challenge_used",
						"Diese Anmeldeanfrage wurde schon bearbeitet. Bitte im Dienst noch einmal auf \"Anmelden\" tippen.");
				case 400 -> ApiException.badRequest("oauth.invalid", "Hydra hat die Anfrage abgelehnt.");
				default -> new ApiException(HttpStatus.BAD_GATEWAY, "oauth.error",
						"Hydra hat mit einem Fehler geantwortet (" + e.getStatusCode().value() + ").");
			};
		}
		catch (ResourceAccessException e) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "oauth.unreachable",
					"Die Anmeldung für andere Dienste (Hydra) ist gerade nicht erreichbar.");
		}
	}

}
