package dev.homeserver.smarthome.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;

import com.jayway.jsonpath.JsonPath;

import dev.homeserver.smarthome.TestJwts;
import dev.homeserver.smarthome.TestcontainersConfiguration;
import dev.homeserver.smarthome.token.ApiTokenService;
import dev.homeserver.smarthome.user.CurrentUser;
import dev.homeserver.smarthome.user.Role;
import dev.homeserver.smarthome.user.UserRepository;

/**
 * SSO with Hydra: the backend answers Hydra's login, consent and logout requests for a person who signed in with
 * Kratos. Runs against a real Hydra (in memory) and plays the browser and the OAuth client: authorization code flow
 * with PKCE, ID token claims, trusted clients without consent, rejection, wrong accounts, deactivated accounts, machines
 * and logout.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OAuthTest {

	private static final String REDIRECT = "http://localhost:5173/cb";
	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	MockMvc mvc;

	@Autowired
	@Qualifier("hydraContainer")
	GenericContainer<?> hydra;

	@Autowired
	UserRepository users;

	@Autowired
	OAuthService service;

	@Autowired
	ApiTokenService tokens;

	/** Plays the browser: keeps Hydra's cookies, does not follow redirects (the web interface's pages do not exist here). */
	HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).cookieHandler(new CookieManager()).build();

	Person erika;

	record Person(UUID identity, String jwt) {
	}

	record Client(String id, String verifier, String challenge) {
	}

	@BeforeEach
	void people() throws Exception {
		erika = person("Erika", "aal1");
	}

	@Test
	void authorizationCodeFlowWithPkceGivesTheClientTheAccountsIdentity() throws Exception {
		var client = client(false);
		String login = start(client, "openid profile email");

		// Not signed in: no answer
		mvc.perform(post("/api/oauth/login").contentType(MediaType.APPLICATION_JSON).content(challenge(login)))
				.andExpect(status().isUnauthorized());

		String afterLogin = as(erika.jwt(), post("/api/oauth/login").contentType(MediaType.APPLICATION_JSON).content(challenge(login)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String consent = param(follow(JsonPath.read(afterLogin, "$.redirectTo")), "consent_challenge");

		// An untrusted client asks: the page shows who and what
		as(erika.jwt(), get("/api/oauth/consent").param("challenge", consent)).andExpect(status().isOk())
				.andExpect(jsonPath("$.redirectTo").doesNotExist())
				.andExpect(jsonPath("$.client.id").value(client.id()))
				.andExpect(jsonPath("$.client.name").value("Test " + client.id()))
				.andExpect(jsonPath("$.client.trusted").value(false))
				.andExpect(jsonPath("$.scopes", org.hamcrest.Matchers.contains("openid", "profile", "email")));
		String afterConsent = as(erika.jwt(), post("/api/oauth/consent/accept").contentType(MediaType.APPLICATION_JSON)
				.content("{\"challenge\": \"" + consent + "\", \"scopes\": [\"openid\", \"profile\", \"email\"]}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		String code = param(follow(JsonPath.read(afterConsent, "$.redirectTo")), "code");
		var token = exchange(client, code);
		var idToken = payload(JsonPath.read(token, "$.id_token"));
		assertThat((String) JsonPath.read(idToken, "$.sub")).isEqualTo(erika.identity().toString());
		assertThat((String) JsonPath.read(idToken, "$.name")).isEqualTo("Erika");
		assertThat((String) JsonPath.read(idToken, "$.email")).contains("@example.com");
		assertThat((Boolean) JsonPath.read(idToken, "$.email_verified")).isFalse();
		assertThat((String) JsonPath.read(idToken, "$.acr")).isEqualTo("aal1");
		assertThat((String) JsonPath.read(token, "$.access_token")).isNotBlank();
		// The code works once
		assertThat(exchangeStatus(client, code)).isEqualTo(400);
	}

	@Test
	void theLogoutPageCanBeAnsweredWithoutASession() throws Exception {
		// The backend never asks Hydra to remember a login, so a logout only has something to do where a session exists:
		// make one by hand through the admin API, as another setup of Hydra could
		var client = client(true);
		String login = start(client, "openid");
		var accept = browser.send(HttpRequest.newBuilder(URI.create(TestcontainersConfiguration.hydraAdminUrl(hydra)
				+ "/admin/oauth2/auth/requests/login/accept?login_challenge=" + login))
				.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(
						"{\"subject\": \"" + erika.identity() + "\", \"remember\": true, \"remember_for\": 3600}"))
				.build(), HttpResponse.BodyHandlers.ofString());
		String consent = param(follow(JsonPath.read(accept.body(), "$.redirect_to")), "consent_challenge");
		String response = as(erika.jwt(), get("/api/oauth/consent").param("challenge", consent)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		var token = exchange(client, param(follow(JsonPath.read(response, "$.redirectTo")), "code"));

		var logout = browser.send(HttpRequest.newBuilder(URI.create(hydraPublic() + "/oauth2/sessions/logout?id_token_hint="
				+ encode(JsonPath.read(token, "$.id_token")))).GET().build(), HttpResponse.BodyHandlers.ofString());
		String logoutChallenge = param(logout.headers().firstValue("location").orElseThrow(), "logout_challenge");
		// No Authorization header: the Kratos session may be over already
		mvc.perform(get("/api/oauth/logout").param("challenge", logoutChallenge)).andExpect(status().isOk())
				.andExpect(jsonPath("$.rpInitiated").value(true))
				.andExpect(jsonPath("$.client.id").value(client.id()));
		mvc.perform(post("/api/oauth/logout/accept").contentType(MediaType.APPLICATION_JSON).content(challenge(logoutChallenge)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.redirectTo").isNotEmpty());
	}

	@Test
	void loggingOutCanBeDeclined() throws Exception {
		var client = client(true);
		String login = start(client, "openid");
		var accept = browser.send(HttpRequest.newBuilder(URI.create(TestcontainersConfiguration.hydraAdminUrl(hydra)
				+ "/admin/oauth2/auth/requests/login/accept?login_challenge=" + login))
				.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(
						"{\"subject\": \"" + erika.identity() + "\", \"remember\": true, \"remember_for\": 3600}"))
				.build(), HttpResponse.BodyHandlers.ofString());
		String consent = param(follow(JsonPath.read(accept.body(), "$.redirect_to")), "consent_challenge");
		String response = as(erika.jwt(), get("/api/oauth/consent").param("challenge", consent)).andReturn().getResponse().getContentAsString();
		var token = exchange(client, param(follow(JsonPath.read(response, "$.redirectTo")), "code"));
		var logout = browser.send(HttpRequest.newBuilder(URI.create(hydraPublic() + "/oauth2/sessions/logout?id_token_hint="
				+ encode(JsonPath.read(token, "$.id_token")))).GET().build(), HttpResponse.BodyHandlers.ofString());
		String logoutChallenge = param(logout.headers().firstValue("location").orElseThrow(), "logout_challenge");
		mvc.perform(post("/api/oauth/logout/reject").contentType(MediaType.APPLICATION_JSON).content(challenge(logoutChallenge)))
				.andExpect(status().isNoContent());
	}

	@Test
	void trustedClientsSkipTheConsentPage() throws Exception {
		var client = client(true);
		String consent = loginAndGetConsentChallenge(client, erika, "openid profile");
		String response = as(erika.jwt(), get("/api/oauth/consent").param("challenge", consent)).andExpect(status().isOk())
				.andExpect(jsonPath("$.client.trusted").value(true)).andReturn().getResponse().getContentAsString();
		// No question: the answer is already the way back to the client, with a code
		assertThat(param(follow(JsonPath.read(response, "$.redirectTo")), "code")).isNotBlank();
	}

	@Test
	void refusingTheConsentSendsAnErrorBackToTheClient() throws Exception {
		var client = client(false);
		String consent = loginAndGetConsentChallenge(client, erika, "openid");
		String response = as(erika.jwt(), post("/api/oauth/consent/reject").contentType(MediaType.APPLICATION_JSON).content(challenge(consent)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String back = follow(JsonPath.read(response, "$.redirectTo"));
		assertThat(param(back, "error")).isEqualTo("access_denied");
		assertThat(back).doesNotContain("code=");
	}

	@Test
	void anUnknownChallengeIsRefused() throws Exception {
		as(erika.jwt(), post("/api/oauth/login").contentType(MediaType.APPLICATION_JSON).content(challenge("does-not-exist")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("oauth.challenge_unknown"));
		as(erika.jwt(), get("/api/oauth/consent").param("challenge", "does-not-exist")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/oauth/logout").param("challenge", "does-not-exist")).andExpect(status().isBadRequest());
		as(erika.jwt(), post("/api/oauth/login").contentType(MediaType.APPLICATION_JSON).content("{\"challenge\": \"\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void aConsentChallengeBelongsToTheAccountThatStartedIt() throws Exception {
		var client = client(false);
		String consent = loginAndGetConsentChallenge(client, erika, "openid");
		var max = person("Max", "aal1");
		as(max.jwt(), get("/api/oauth/consent").param("challenge", consent)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("oauth.wrong_account"));
		as(max.jwt(), post("/api/oauth/consent/accept").contentType(MediaType.APPLICATION_JSON)
				.content("{\"challenge\": \"" + consent + "\", \"scopes\": [\"openid\"]}")).andExpect(status().isForbidden());
		as(max.jwt(), post("/api/oauth/consent/reject").contentType(MediaType.APPLICATION_JSON).content(challenge(consent)))
				.andExpect(status().isForbidden());
		// Erika can still answer
		as(erika.jwt(), get("/api/oauth/consent").param("challenge", consent)).andExpect(status().isOk());
	}

	@Test
	void onlyAskedScopesAreGrantedAndOpenidStays() throws Exception {
		var client = client(false);
		String consent = loginAndGetConsentChallenge(client, erika, "openid profile");
		as(erika.jwt(), post("/api/oauth/consent/accept").contentType(MediaType.APPLICATION_JSON)
				.content("{\"challenge\": \"" + consent + "\", \"scopes\": [\"openid\", \"email\"]}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("oauth.scope_invalid"));
		as(erika.jwt(), post("/api/oauth/consent/accept").contentType(MediaType.APPLICATION_JSON)
				.content("{\"challenge\": \"" + consent + "\", \"scopes\": [\"profile\"]}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("oauth.openid_required"));
		// Only openid is fine: then the ID token carries no name
		String response = as(erika.jwt(), post("/api/oauth/consent/accept").contentType(MediaType.APPLICATION_JSON)
				.content("{\"challenge\": \"" + consent + "\", \"scopes\": [\"openid\"]}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		var token = exchange(client, param(follow(JsonPath.read(response, "$.redirectTo")), "code"));
		var idToken = payload(JsonPath.read(token, "$.id_token"));
		assertThat(JsonPath.<Map<String, Object>>read(idToken, "$")).doesNotContainKeys("name", "email");
	}

	@Test
	void machinesCannotSignInToOtherServices() throws Exception {
		long ki = users.findAll().stream().filter(u -> u.isMachine() && u.hasRole(Role.KI)).findFirst().orElseThrow().getId();
		String aiToken = tokens.create(ki, "oauth test").secret();
		var client = client(false);
		String login = start(client, "openid");
		as(aiToken, post("/api/oauth/login").contentType(MediaType.APPLICATION_JSON).content(challenge(login)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("oauth.machine"));
	}

	@Test
	void aDeactivatedAccountIsRejectedTowardTheClient() throws Exception {
		// The sign-in layer already refuses such an account; the service rejects as well, so Hydra does not wait
		as(erika.jwt(), get("/api/me")).andExpect(status().isOk());
		var account = users.findAll().stream().filter(u -> erika.identity().equals(u.getIdentityId())).findFirst().orElseThrow();
		var current = CurrentUser.of(account, "erika@example.com");
		var client = client(false);
		String login = start(client, "openid");
		account.setActive(false);
		users.saveAndFlush(account);
		try {
			String back = follow(service.login(current, "aal1", login));
			assertThat(param(back, "error")).isEqualTo("access_denied");
			assertThat(back).doesNotContain("code=");
			// And the HTTP API refuses the account's token right away
			as(erika.jwt(), get("/api/oauth/consent").param("challenge", "x")).andExpect(status().is4xxClientError());
		}
		finally {
			account.setActive(true);
			users.saveAndFlush(account);
		}
	}

	// ---- The browser and the client ----

	private String loginAndGetConsentChallenge(Client client, Person person, String scope) throws Exception {
		String login = start(client, scope);
		String response = as(person.jwt(), post("/api/oauth/login").contentType(MediaType.APPLICATION_JSON).content(challenge(login)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return param(follow(JsonPath.read(response, "$.redirectTo")), "consent_challenge");
	}

	/** Registers an OAuth2 client (public, PKCE) at Hydra's admin API; {@code trusted} is the metadata flag. */
	private Client client(boolean trusted) throws Exception {
		String id = "test-" + UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"client_id\": \"" + id + "\", \"client_name\": \"Test " + id + "\", \"redirect_uris\": [\"" + REDIRECT
				+ "\"], \"grant_types\": [\"authorization_code\", \"refresh_token\"], \"response_types\": [\"code\"], "
				+ "\"scope\": \"openid profile email offline_access\", \"token_endpoint_auth_method\": \"none\", "
				+ "\"metadata\": {\"trusted\": " + trusted + "}}";
		var response = browser.send(HttpRequest.newBuilder(URI.create(TestcontainersConfiguration.hydraAdminUrl(hydra) + "/admin/clients"))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		String challenge = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		return new Client(id, verifier, challenge);
	}

	/** The client sends the browser to Hydra; Hydra sends it on to the login page, the answer is the login challenge. */
	private String start(Client client, String scope) throws Exception {
		String url = hydraPublic() + "/oauth2/auth?client_id=" + client.id() + "&response_type=code&scope=" + encode(scope)
				+ "&redirect_uri=" + encode(REDIRECT) + "&state=" + UUID.randomUUID() + "&code_challenge=" + client.challenge()
				+ "&code_challenge_method=S256";
		var response = browser.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isIn(302, 303);
		return param(response.headers().firstValue("location").orElseThrow(), "login_challenge");
	}

	/** Follows one redirect of Hydra (its address in the answers is the public one of production, here the mapped port). */
	private String follow(String redirectTo) throws Exception {
		String url = redirectTo.replace("http://localhost:4444", hydraPublic());
		var response = browser.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isIn(302, 303);
		return response.headers().firstValue("location").orElseThrow();
	}

	private String exchange(Client client, String code) throws Exception {
		var response = tokenRequest(client, code);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return response.body();
	}

	private int exchangeStatus(Client client, String code) throws Exception {
		return tokenRequest(client, code).statusCode();
	}

	private HttpResponse<String> tokenRequest(Client client, String code) throws Exception {
		String form = "grant_type=authorization_code&code=" + encode(code) + "&redirect_uri=" + encode(REDIRECT) + "&client_id="
				+ client.id() + "&code_verifier=" + client.verifier();
		return browser.send(HttpRequest.newBuilder(URI.create(hydraPublic() + "/oauth2/token"))
				.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private String hydraPublic() {
		return TestcontainersConfiguration.hydraPublicUrl(hydra);
	}

	// ---- Helpers ----

	private Person person(String name, String aal) throws Exception {
		UUID identity = UUID.randomUUID();
		String email = name.toLowerCase() + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
		var claims = TestJwts.claims(identity, Map.of("email", email, "name", name, "preferred_username", name.toLowerCase(), "aal", aal)).build();
		return new Person(identity, TestJwts.sign(claims));
	}

	private static String challenge(String value) {
		return "{\"challenge\": \"" + value + "\"}";
	}

	private static String param(String url, String name) {
		String query = URI.create(url).getRawQuery();
		Map<String, String> params = new HashMap<>();
		for (String pair : query.split("&")) {
			String[] kv = pair.split("=", 2);
			params.put(kv[0], kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
		}
		assertThat(params).as(url).containsKey(name);
		return params.get(name);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String payload(String jwt) {
		return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

}
