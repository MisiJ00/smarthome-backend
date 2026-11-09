package dev.homeserver.smarthome.oauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import dev.homeserver.smarthome.error.ApiException;
import dev.homeserver.smarthome.oauth.HydraAdminClient.Client;
import dev.homeserver.smarthome.user.CurrentUser;
import dev.homeserver.smarthome.user.User;
import dev.homeserver.smarthome.user.UserRepository;

/**
 * SSO for other services (Immich, Nextcloud ...): Hydra is the OpenID Connect server, Kratos stays the account and
 * login system. The web interface lets the person sign in with Kratos as usual and then asks this service to answer
 * Hydra's login and consent requests, with the person's own Kratos JWT. Hydra's admin API is only reachable from here,
 * never from a browser, and every answer is checked against the signed-in account: the subject Hydra hands out is the
 * Kratos identity id, accounts that are deactivated or machines get nothing, and a consent challenge can only be
 * answered by the account it was started for.
 */
@Service
public class OAuthService {

	/** What is handed out at all; other scopes a client asks for are left out, whatever Hydra has registered. */
	static final List<String> SUPPORTED_SCOPES = List.of("openid", "profile", "email", "offline_access");

	private final HydraAdminClient hydra;
	private final UserRepository users;

	public OAuthService(HydraAdminClient hydra, UserRepository users) {
		this.hydra = hydra;
		this.users = users;
	}

	public record ClientInfo(String id, String name, boolean trusted) {
	}

	/** What the consent page shows; {@code redirectTo} is set instead when no question is needed. */
	public record ConsentView(String redirectTo, ClientInfo client, List<String> scopes) {
	}

	public record LogoutView(ClientInfo client, boolean rpInitiated) {
	}

	/**
	 * Accepts Hydra's login request for the signed-in person; the caller has a valid Kratos session (the JWT only
	 * exists when the second factor is satisfied, if the account has one). Returns the address to go on to.
	 *
	 * @param aal the JWT's {@code aal} claim (aal1, aal2), handed to the client as {@code acr}
	 */
	public String login(CurrentUser user, String aal, String challenge) {
		requireHuman(user);
		var request = hydra.loginRequest(challenge);
		if (activeAccount(user) == null) {
			return hydra.rejectLogin(challenge, "access_denied", "Dieses Konto ist deaktiviert.");
		}
		String subject = user.identityId().toString();
		if (request.skip() && !subject.equals(request.subject())) {
			// Hydra still has another account's login: do not hand it over to this one
			return hydra.rejectLogin(challenge, "login_required", "Bitte noch einmal anmelden.");
		}
		return hydra.acceptLogin(challenge, subject, aal);
	}

	public ConsentView consent(CurrentUser user, String challenge) {
		requireHuman(user);
		var request = hydra.consentRequest(challenge);
		requireOwner(user, request.subject());
		var scopes = grantable(request.requestedScope());
		var client = info(request.client());
		if (activeAccount(user) == null) {
			return new ConsentView(hydra.rejectConsent(challenge, "access_denied", "Dieses Konto ist deaktiviert."), client, scopes);
		}
		// Own services marked as trusted (client metadata, only an admin can set it) and requests Hydra already has an
		// answer to do not ask
		if (client.trusted() || request.skip()) {
			return new ConsentView(accept(user, challenge, request.requestedAudience(), scopes), client, scopes);
		}
		return new ConsentView(null, client, scopes);
	}

	/** The person agrees to (some of) the scopes; {@code openid} cannot be taken away, the client needs it to sign in. */
	public String acceptConsent(CurrentUser user, String challenge, List<String> granted) {
		requireHuman(user);
		var request = hydra.consentRequest(challenge);
		requireOwner(user, request.subject());
		if (activeAccount(user) == null) {
			return hydra.rejectConsent(challenge, "access_denied", "Dieses Konto ist deaktiviert.");
		}
		var allowed = grantable(request.requestedScope());
		var scopes = granted == null ? List.<String>of() : granted;
		if (!allowed.containsAll(scopes)) {
			throw ApiException.badRequest("oauth.scope_invalid", "Diese Berechtigung wurde nicht angefragt.");
		}
		if (allowed.contains("openid") && !scopes.contains("openid")) {
			throw ApiException.badRequest("oauth.openid_required",
					"Ohne die Identität kann sich der Dienst nicht anmelden. Bitte ablehnen oder alles erlauben.");
		}
		return accept(user, challenge, request.requestedAudience(), scopes);
	}

	public String rejectConsent(CurrentUser user, String challenge) {
		requireHuman(user);
		var request = hydra.consentRequest(challenge);
		requireOwner(user, request.subject());
		return hydra.rejectConsent(challenge, "access_denied", "Die Anmeldung wurde abgelehnt.");
	}

	/** What the logout page says; no sign-in needed (the session may be over already), the challenge is the secret. */
	public LogoutView logout(String challenge) {
		var request = hydra.logoutRequest(challenge);
		return new LogoutView(info(request.client()), request.rpInitiated());
	}

	public String acceptLogout(String challenge) {
		return hydra.acceptLogout(challenge);
	}

	public void rejectLogout(String challenge) {
		hydra.rejectLogout(challenge);
	}

	private String accept(CurrentUser user, String challenge, List<String> audience, List<String> scopes) {
		var account = activeAccount(user);
		Map<String, Object> claims = new LinkedHashMap<>();
		if (scopes.contains("profile")) {
			claims.put("name", account.getName());
			claims.put("preferred_username", account.getUsername());
		}
		if (scopes.contains("email")) {
			claims.put("email", account.getEmail());
			// Kratos does not verify addresses yet (no mail server), so do not claim it
			claims.put("email_verified", false);
		}
		return hydra.acceptConsent(challenge, scopes, audience == null ? List.of() : audience, claims);
	}

	private User activeAccount(CurrentUser user) {
		return users.findById(user.id()).filter(User::isActive).orElse(null);
	}

	private static List<String> grantable(List<String> requested) {
		return requested == null ? List.of() : requested.stream().filter(SUPPORTED_SCOPES::contains).toList();
	}

	private static ClientInfo info(Client client) {
		return client == null ? new ClientInfo("", "Unbekannter Dienst", false)
				: new ClientInfo(client.clientId(), client.displayName(), client.trusted());
	}

	private static void requireHuman(CurrentUser user) {
		if (user.identityId() == null) {
			throw ApiException.forbidden("oauth.machine", "Nur Menschen können sich bei anderen Diensten anmelden.");
		}
	}

	private static void requireOwner(CurrentUser user, String subject) {
		if (subject == null || !subject.equals(user.identityId().toString())) {
			throw ApiException.forbidden("oauth.wrong_account", "Diese Anmeldeanfrage gehört zu einem anderen Konto.");
		}
	}

}
