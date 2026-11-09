package dev.homeserver.smarthome.oauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.homeserver.smarthome.audit.AuditResult;
import dev.homeserver.smarthome.audit.AuditService;
import dev.homeserver.smarthome.audit.Via;
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
 *
 * <p>Everything is written to the audit log ({@code sso_login}, {@code sso_consent}, {@code sso_logout}): who, which
 * service, which scopes, what became of it. An answer to Hydra and its entry go together: the entry is written in one
 * transaction with the call to Hydra, so a failing write stops the answer, and an answer Hydra refuses leaves no entry
 * behind (the very rare failure of the commit itself, after Hydra has accepted, cannot be undone). Refusals that end in
 * an error for the caller (machines, wrong account) are written before the error. Never in the log: challenges, codes,
 * tokens, secrets; names that come from the service are shortened.
 */
@Service
public class OAuthService {

	/** What is handed out at all; other scopes a client asks for are left out, whatever Hydra has registered. */
	static final List<String> SUPPORTED_SCOPES = List.of("openid", "profile", "email", "offline_access");

	static final String ACTION_LOGIN = "sso_login";
	static final String ACTION_CONSENT = "sso_consent";
	static final String ACTION_LOGOUT = "sso_logout";

	/** Texts from outside (client name and id) are cut to this many characters in the log. */
	static final int MAX_LOGGED_TEXT = 100;

	private final HydraAdminClient hydra;
	private final UserRepository users;
	private final AuditService audit;
	private final TransactionTemplate transaction;

	public OAuthService(HydraAdminClient hydra, UserRepository users, AuditService audit,
			PlatformTransactionManager transactions) {
		this.hydra = hydra;
		this.users = users;
		this.audit = audit;
		this.transaction = new TransactionTemplate(transactions);
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
	public String login(CurrentUser user, Via via, String aal, String challenge) {
		var request = hydra.loginRequest(challenge);
		var client = request.client();
		requireHuman(user, via, ACTION_LOGIN, client);
		if (activeAccount(user) == null) {
			return audited(user, via, ACTION_LOGIN, AuditResult.REJECTED, details(client, "reason", "deactivated"),
					() -> hydra.rejectLogin(challenge, "access_denied", "Dieses Konto ist deaktiviert."));
		}
		String subject = user.identityId().toString();
		if (request.skip() && !subject.equals(request.subject())) {
			// Hydra still has another account's login: do not hand it over to this one
			return audited(user, via, ACTION_LOGIN, AuditResult.REJECTED, details(client, "reason", "login_required"),
					() -> hydra.rejectLogin(challenge, "login_required", "Bitte noch einmal anmelden."));
		}
		return audited(user, via, ACTION_LOGIN, AuditResult.ACCEPTED, details(client, "acr", aal),
				() -> hydra.acceptLogin(challenge, subject, aal));
	}

	public ConsentView consent(CurrentUser user, Via via, String challenge) {
		var request = hydra.consentRequest(challenge);
		requireHuman(user, via, ACTION_CONSENT, request.client());
		requireOwner(user, via, request);
		var scopes = grantable(request.requestedScope());
		var client = info(request.client());
		if (activeAccount(user) == null) {
			var redirect = audited(user, via, ACTION_CONSENT, AuditResult.REJECTED,
					details(request.client(), "reason", "deactivated"),
					() -> hydra.rejectConsent(challenge, "access_denied", "Dieses Konto ist deaktiviert."));
			return new ConsentView(redirect, client, scopes);
		}
		// Own services marked as trusted (client metadata, only an admin can set it) and requests Hydra already has an
		// answer to do not ask; the log says that the question was skipped, and why
		if (client.trusted() || request.skip()) {
			var details = details(request.client(), "reason", client.trusted() ? "trusted" : "remembered");
			details.put("scopes", scopes);
			var redirect = audited(user, via, ACTION_CONSENT, AuditResult.SKIPPED, details,
					() -> accept(user, challenge, request.requestedAudience(), scopes));
			return new ConsentView(redirect, client, scopes);
		}
		return new ConsentView(null, client, scopes);
	}

	/** The person agrees to (some of) the scopes; {@code openid} cannot be taken away, the client needs it to sign in. */
	public String acceptConsent(CurrentUser user, Via via, String challenge, List<String> granted) {
		var request = hydra.consentRequest(challenge);
		requireHuman(user, via, ACTION_CONSENT, request.client());
		requireOwner(user, via, request);
		if (activeAccount(user) == null) {
			return audited(user, via, ACTION_CONSENT, AuditResult.REJECTED, details(request.client(), "reason", "deactivated"),
					() -> hydra.rejectConsent(challenge, "access_denied", "Dieses Konto ist deaktiviert."));
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
		var details = details(request.client());
		details.put("scopes", scopes);
		return audited(user, via, ACTION_CONSENT, AuditResult.ACCEPTED, details,
				() -> accept(user, challenge, request.requestedAudience(), scopes));
	}

	public String rejectConsent(CurrentUser user, Via via, String challenge) {
		var request = hydra.consentRequest(challenge);
		requireHuman(user, via, ACTION_CONSENT, request.client());
		requireOwner(user, via, request);
		return audited(user, via, ACTION_CONSENT, AuditResult.REJECTED, details(request.client(), "reason", "refused"),
				() -> hydra.rejectConsent(challenge, "access_denied", "Die Anmeldung wurde abgelehnt."));
	}

	/** What the logout page says; no sign-in needed (the session may be over already), the challenge is the secret. */
	public LogoutView logout(String challenge) {
		var request = hydra.logoutRequest(challenge);
		return new LogoutView(info(request.client()), request.rpInitiated());
	}

	/** The person confirmed on the logout page (no sign-in needed). The log names the account Hydra has the session of. */
	public String acceptLogout(String challenge) {
		var request = hydra.logoutRequest(challenge);
		return audited(accountOf(request.subject()), Via.WEB, ACTION_LOGOUT, AuditResult.ACCEPTED, details(request.client()),
				() -> hydra.acceptLogout(challenge));
	}

	public void rejectLogout(String challenge) {
		var request = hydra.logoutRequest(challenge);
		audited(accountOf(request.subject()), Via.WEB, ACTION_LOGOUT, AuditResult.REJECTED, details(request.client()), () -> {
			hydra.rejectLogout(challenge);
			return null;
		});
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

	/** Writes the entry and makes the call in one transaction: no entry without the answer, no answer without the entry. */
	private <T> T audited(CurrentUser user, Via via, String action, AuditResult result, Map<String, Object> params,
			Supplier<T> call) {
		return transaction.execute(status -> {
			audit.record(user, via, null, action, params, result, null);
			return call.get();
		});
	}

	/** Who the log names for a logout: the account Hydra has the session of, if it is one of ours. */
	private CurrentUser accountOf(String subject) {
		if (subject == null || subject.isBlank()) {
			return null;
		}
		try {
			return users.findByIdentityId(UUID.fromString(subject)).map(u -> CurrentUser.of(u, u.getEmail())).orElse(null);
		}
		catch (IllegalArgumentException e) {
			return null;
		}
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

	/** The service in the log: its id and name, shortened and without control characters, plus optional pairs. */
	private static Map<String, Object> details(Client client, String... pairs) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("clientId", logged(client == null ? null : client.clientId()));
		details.put("clientName", logged(client == null ? null : client.displayName()));
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			if (pairs[i + 1] != null) {
				details.put(pairs[i], logged(pairs[i + 1]));
			}
		}
		return details;
	}

	static String logged(String text) {
		if (text == null) {
			return "";
		}
		String clean = text.replaceAll("\\p{Cntrl}", " ").strip();
		return clean.length() <= MAX_LOGGED_TEXT ? clean : clean.substring(0, MAX_LOGGED_TEXT) + "…";
	}

	private void requireHuman(CurrentUser user, Via via, String action, Client client) {
		if (user.identityId() == null) {
			audit.record(user, via, null, action, details(client, "reason", "machine"), AuditResult.REJECTED, null);
			throw ApiException.forbidden("oauth.machine", "Nur Menschen können sich bei anderen Diensten anmelden.");
		}
	}

	private void requireOwner(CurrentUser user, Via via, HydraAdminClient.ConsentRequest request) {
		String subject = request.subject();
		if (subject == null || !subject.equals(user.identityId().toString())) {
			audit.record(user, via, null, ACTION_CONSENT, details(request.client(), "reason", "wrong_account"),
					AuditResult.REJECTED, null);
			throw ApiException.forbidden("oauth.wrong_account", "Diese Anmeldeanfrage gehört zu einem anderen Konto.");
		}
	}

}
