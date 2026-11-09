package dev.homeserver.smarthome.trusteddevice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import dev.homeserver.smarthome.RecordingDeviceAdapter;
import dev.homeserver.smarthome.TestJwts;
import dev.homeserver.smarthome.TestcontainersConfiguration;
import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.device.DiscoveredDevice;
import dev.homeserver.smarthome.token.ApiTokenService;
import dev.homeserver.smarthome.user.Role;
import dev.homeserver.smarthome.user.UserRepository;

/**
 * Approving with a browser passkey (WebAuthn): the browser registers a platform credential right after a fresh login,
 * and each approval is one gesture whose assertion signs the hash of the approval text. The test plays the
 * authenticator: it builds attestation objects and assertions the way a browser hands them over.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WebAuthnDeviceTest {

	private static final String ADMIN = TestJwts.token(UUID.randomUUID(), "admin@example.com", "Admin");
	private static final String ORIGIN = "http://localhost:5173";
	private static final String EVIL = "https://evil.example";
	private static final String RP_ID = "localhost";
	private static final int UP = 0x01;
	private static final int UV = 0x04;
	private static final int AT = 0x40;

	@Autowired
	MockMvc mvc;

	@Autowired
	DeviceRepository devices;

	@Autowired
	UserRepository users;

	@Autowired
	ApiTokenService tokens;

	@Autowired
	RecordingDeviceAdapter adapter;

	Device door;
	Person erika;

	record Person(UUID identity, String email, String name, String jwt) {
	}

	@BeforeEach
	void home() throws Exception {
		door = addDevice("Haustür " + suffix());
		erika = resident("Erika");
	}

	@Test
	void browserPasskeyApprovesTheOwnDoorRequest() throws Exception {
		Authenticator passkey = new Authenticator();
		String created = register(erika.jwt(), passkey, "Erikas Laptop").andExpect(status().isCreated())
				.andExpect(jsonPath("$.kind").value("webauthn"))
				.andExpect(jsonPath("$.credentialId").value(passkey.credentialIdText()))
				.andExpect(jsonPath("$.active").value(true))
				.andReturn().getResponse().getContentAsString();
		long deviceId = id(created);
		as(erika.jwt(), get("/api/me/trusted-devices")).andExpect(jsonPath("$[*].name", contains("Erikas Laptop")));

		long confirmation = requestUnlock(erika.jwt());
		as(erika.jwt(), get("/api/confirmations/" + confirmation)).andExpect(jsonPath("$.canApproveWithPhone").value(true));
		var challenge = challenge(erika.jwt(), confirmation);

		approve(erika.jwt(), confirmation, deviceId, challenge.nonce(),
				passkey.assertion(challenge.payload(), 1, UP | UV, ORIGIN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("approved"));
		assertThat(adapter.sentTo(door.getExternalId())).containsExactly(Map.of("state", "UNLOCK"));
		as(ADMIN, get("/api/audit").param("deviceId", String.valueOf(door.getId())).param("result", "approved"))
				.andExpect(jsonPath("$.items[0].params.approvedWith").value("Erikas Laptop"));
	}

	@Test
	void registrationNeedsAFreshLoginAndAPerson() throws Exception {
		var stale = jwt(erika, Instant.now().minusSeconds(3600).toString());
		as(stale, post("/api/me/trusted-devices/webauthn/challenge")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.reason").value("fresh_login_required"));
		as(aiToken(), post("/api/me/trusted-devices/webauthn/challenge")).andExpect(status().isForbidden());

		// A challenge from a fresh login is no good for a registration after the login has gone stale
		Authenticator passkey = new Authenticator();
		var options = options(erika.jwt());
		postRegistration(stale, registration("Alt", passkey.creation(options, RP_ID, UP | UV | AT, ORIGIN)))
				.andExpect(status().isForbidden());
		as(erika.jwt(), get("/api/me/trusted-devices")).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void optionsCarryTheRelyingPartyAndExcludeKnownCredentials() throws Exception {
		Authenticator first = new Authenticator();
		register(erika.jwt(), first, "Eins").andExpect(status().isCreated());
		as(erika.jwt(), post("/api/me/trusted-devices/webauthn/challenge")).andExpect(status().isOk())
				.andExpect(jsonPath("$.rpId").value(RP_ID))
				.andExpect(jsonPath("$.rpName").value("Smart Home"))
				.andExpect(jsonPath("$.userName").value("Erika (Freigabe)"))
				.andExpect(jsonPath("$.excludeCredentialIds[0]").value(first.credentialIdText()))
				.andExpect(jsonPath("$.challenge").isNotEmpty())
				.andExpect(jsonPath("$.userHandle").isNotEmpty());
	}

	@Test
	void badRegistrationsAreRefused() throws Exception {
		Authenticator passkey = new Authenticator();
		// Without user verification (e.g. a plain security key tap)
		var o = options(erika.jwt());
		postRegistration(erika.jwt(), registration("X", passkey.creation(o, RP_ID, UP | AT, ORIGIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("webauthn.user_verification_required"));
		// Another site's origin
		o = options(erika.jwt());
		postRegistration(erika.jwt(), registration("X", passkey.creation(o, RP_ID, UP | UV | AT, EVIL)))
				.andExpect(status().isBadRequest());
		// Another relying party
		o = options(erika.jwt());
		postRegistration(erika.jwt(), registration("X", passkey.creation(o, "evil.example", UP | UV | AT, ORIGIN)))
				.andExpect(status().isBadRequest());
		// A challenge the server never issued
		postRegistration(erika.jwt(), registration("X",
				passkey.creation("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", RP_ID, UP | UV | AT, ORIGIN, -7)))
				.andExpect(status().isForbidden());
		// The same challenge twice, even when the second try would be fine
		o = options(erika.jwt());
		postRegistration(erika.jwt(), registration("X", passkey.creation(o, RP_ID, UP | AT, ORIGIN)))
				.andExpect(status().isBadRequest());
		postRegistration(erika.jwt(), registration("X", passkey.creation(o, RP_ID, UP | UV | AT, ORIGIN)))
				.andExpect(status().isForbidden());
		// Not an ES256 key
		o = options(erika.jwt());
		postRegistration(erika.jwt(), registration("X", passkey.creation(o.challenge(), RP_ID, UP | UV | AT, ORIGIN, -257)))
				.andExpect(status().isBadRequest());
		// The same credential cannot be registered twice
		register(erika.jwt(), passkey, "Eins").andExpect(status().isCreated());
		register(erika.jwt(), passkey, "Zwei").andExpect(status().isConflict());
		as(erika.jwt(), get("/api/me/trusted-devices")).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void badAssertionsAreRefused() throws Exception {
		Authenticator passkey = new Authenticator();
		long deviceId = id(register(erika.jwt(), passkey, "Laptop").andReturn().getResponse().getContentAsString());
		long confirmation = requestUnlock(erika.jwt());

		// No user verification this time (presence only)
		var c = challenge(erika.jwt(), confirmation);
		approve(erika.jwt(), confirmation, deviceId, c.nonce(), passkey.assertion(c.payload(), 1, UP, ORIGIN))
				.andExpect(status().isForbidden());
		// Wrong origin
		c = challenge(erika.jwt(), confirmation);
		approve(erika.jwt(), confirmation, deviceId, c.nonce(), passkey.assertion(c.payload(), 2, UP | UV, EVIL))
				.andExpect(status().isForbidden());
		// Signed text of another approval (right nonce, wrong challenge inside clientDataJSON)
		c = challenge(erika.jwt(), confirmation);
		approve(erika.jwt(), confirmation, deviceId, c.nonce(), passkey.assertion(c.payload() + "x", 3, UP | UV, ORIGIN))
				.andExpect(status().isForbidden());
		// Another authenticator's key
		c = challenge(erika.jwt(), confirmation);
		approve(erika.jwt(), confirmation, deviceId, c.nonce(),
				new Authenticator().assertion(c.payload(), 4, UP | UV, ORIGIN)).andExpect(status().isForbidden());
		// Missing WebAuthn fields
		c = challenge(erika.jwt(), confirmation);
		as(erika.jwt(), post("/api/confirmations/" + confirmation + "/approve").contentType(MediaType.APPLICATION_JSON)
				.content("{\"trustedDeviceId\": " + deviceId + ", \"nonce\": \"" + c.nonce()
						+ "\", \"signature\": \"AAAA\"}"))
				.andExpect(status().isForbidden());
		assertThat(adapter.sentTo(door.getExternalId())).isEmpty();

		// The right one still works, and replaying it does not
		c = challenge(erika.jwt(), confirmation);
		var good = passkey.assertion(c.payload(), 5, UP | UV, ORIGIN);
		approve(erika.jwt(), confirmation, deviceId, c.nonce(), good).andExpect(status().isOk());
		approve(erika.jwt(), confirmation, deviceId, c.nonce(), good).andExpect(status().is4xxClientError());
		assertThat(adapter.sentTo(door.getExternalId())).hasSize(1);
	}

	@Test
	void aSignCounterThatDoesNotRiseIsRefusedButZeroCountersAreFine() throws Exception {
		Authenticator counting = new Authenticator();
		long id = id(register(erika.jwt(), counting, "Mit Zähler").andReturn().getResponse().getContentAsString());
		long first = requestUnlock(erika.jwt());
		var c = challenge(erika.jwt(), first);
		approve(erika.jwt(), first, id, c.nonce(), counting.assertion(c.payload(), 7, UP | UV, ORIGIN))
				.andExpect(status().isOk());
		long second = requestUnlock(erika.jwt());
		c = challenge(erika.jwt(), second);
		// Same or lower counter than before: looks like a clone
		approve(erika.jwt(), second, id, c.nonce(), counting.assertion(c.payload(), 7, UP | UV, ORIGIN))
				.andExpect(status().isForbidden());
		c = challenge(erika.jwt(), second);
		approve(erika.jwt(), second, id, c.nonce(), counting.assertion(c.payload(), 8, UP | UV, ORIGIN))
				.andExpect(status().isOk());

		// Authenticators that always send 0 (Touch ID, Windows Hello) keep working
		Person max = resident("Max");
		Authenticator zero = new Authenticator();
		long zeroId = id(register(max.jwt(), zero, "Hello").andReturn().getResponse().getContentAsString());
		for (int i = 0; i < 2; i++) {
			long confirmation = requestUnlock(max.jwt());
			var challenge = challenge(max.jwt(), confirmation);
			approve(max.jwt(), confirmation, zeroId, challenge.nonce(),
					zero.assertion(challenge.payload(), 0, UP | UV, ORIGIN)).andExpect(status().isOk());
		}
	}

	@Test
	void aRevokedPasskeyCannotApprove() throws Exception {
		Authenticator passkey = new Authenticator();
		long deviceId = id(register(erika.jwt(), passkey, "Laptop").andReturn().getResponse().getContentAsString());
		as(erika.jwt(), delete("/api/me/trusted-devices/" + deviceId)).andExpect(status().isNoContent());
		// The audit entry says it was a browser passkey, not a phone
		as(ADMIN, get("/api/audit").param("result", "revoked")).andExpect(jsonPath("$.items[0].params.kind").value("webauthn"));
		long confirmation = requestUnlock(erika.jwt());
		as(erika.jwt(), post("/api/confirmations/" + confirmation + "/challenge")).andExpect(status().isConflict());
	}

	// ---- The authenticator ----

	/** A platform authenticator with one ES256 credential. */
	static final class Authenticator {

		private final KeyPair key = keyPair();
		private final byte[] credentialId = random(32);

		String credentialIdText() {
			return b64url(credentialId);
		}

		Map<String, String> creation(Options options, String rpId, int flags, String origin) {
			return creation(options.challenge(), rpId, flags, origin, -7);
		}

		/** What {@code navigator.credentials.create} hands over. */
		Map<String, String> creation(String challenge, String rpId, int flags, String origin, int alg) {
			var point = ((ECPublicKey) key.getPublic()).getW();
			var cose = new Cbor();
			cose.map(5).integer(1).integer(2).integer(3).integer(alg).integer(-1).integer(1)
					.integer(-2).bytes(fixed(point.getAffineX())).integer(-3).bytes(fixed(point.getAffineY()));
			var authData = new ByteArrayOutputStream();
			authData.writeBytes(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
			authData.write(flags);
			authData.writeBytes(ByteBuffer.allocate(4).putInt(0).array());
			authData.writeBytes(new byte[16]);
			authData.writeBytes(new byte[] {(byte) (credentialId.length >> 8), (byte) credentialId.length});
			authData.writeBytes(credentialId);
			authData.writeBytes(cose.toBytes());
			var attestation = new Cbor();
			attestation.map(3).text("fmt").text("none").text("attStmt").map(0).text("authData")
					.bytes(authData.toByteArray());
			String clientData = clientData("webauthn.create", challenge, origin);
			Map<String, String> out = new LinkedHashMap<>();
			out.put("clientDataJson", b64url(clientData.getBytes(StandardCharsets.UTF_8)));
			out.put("attestationObject", b64url(attestation.toBytes()));
			return out;
		}

		/** What {@code navigator.credentials.get} hands over for the approval text. */
		Map<String, String> assertion(String payload, long counter, int flags, String origin) throws Exception {
			String challenge = b64url(sha256(payload.getBytes(StandardCharsets.UTF_8)));
			byte[] clientData = clientData("webauthn.get", challenge, origin).getBytes(StandardCharsets.UTF_8);
			var authData = new ByteArrayOutputStream();
			authData.writeBytes(sha256(RP_ID.getBytes(StandardCharsets.UTF_8)));
			authData.write(flags);
			authData.writeBytes(ByteBuffer.allocate(4).putInt((int) counter).array());
			var signer = Signature.getInstance("SHA256withECDSA");
			signer.initSign(key.getPrivate());
			signer.update(authData.toByteArray());
			signer.update(sha256(clientData));
			Map<String, String> out = new LinkedHashMap<>();
			out.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
			out.put("authenticatorData", b64url(authData.toByteArray()));
			out.put("clientDataJson", b64url(clientData));
			return out;
		}

		private static String clientData(String type, String challenge, String origin) {
			return "{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin
					+ "\",\"crossOrigin\":false}";
		}

		private static KeyPair keyPair() {
			try {
				var generator = KeyPairGenerator.getInstance("EC");
				generator.initialize(new ECGenParameterSpec("secp256r1"));
				return generator.generateKeyPair();
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

	}

	/** Just enough CBOR to write attestation objects. */
	static final class Cbor {

		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		Cbor map(int size) {
			head(5, size);
			return this;
		}

		Cbor text(String value) {
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			head(3, bytes.length);
			out.writeBytes(bytes);
			return this;
		}

		Cbor bytes(byte[] value) {
			head(2, value.length);
			out.writeBytes(value);
			return this;
		}

		Cbor integer(long value) {
			if (value >= 0) {
				head(0, value);
			}
			else {
				head(1, -1 - value);
			}
			return this;
		}

		byte[] toBytes() {
			return out.toByteArray();
		}

		private void head(int major, long value) {
			if (value < 24) {
				out.write(major << 5 | (int) value);
			}
			else if (value < 256) {
				out.write(major << 5 | 24);
				out.write((int) value);
			}
			else {
				out.write(major << 5 | 25);
				out.write((int) (value >> 8));
				out.write((int) value);
			}
		}

	}

	// ---- Requests ----

	record Options(String challenge) {
	}

	record Challenge(String nonce, String payload) {
	}

	private Options options(String bearer) throws Exception {
		String response = as(bearer, post("/api/me/trusted-devices/webauthn/challenge")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return new Options(JsonPath.read(response, "$.challenge"));
	}

	/** Gets a challenge and registers the authenticator's credential with it. */
	private ResultActions register(String bearer, Authenticator passkey, String name) throws Exception {
		var o = options(bearer);
		return postRegistration(bearer, registration(name, passkey.creation(o, RP_ID, UP | UV | AT, ORIGIN)));
	}

	private ResultActions postRegistration(String bearer, String body) throws Exception {
		return as(bearer, post("/api/me/trusted-devices/webauthn").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String registration(String name, Map<String, String> created) {
		return "{\"name\": \"" + name + "\", \"clientDataJson\": \"" + created.get("clientDataJson")
				+ "\", \"attestationObject\": \"" + created.get("attestationObject") + "\"}";
	}

	private Challenge challenge(String bearer, long confirmation) throws Exception {
		String response = as(bearer, post("/api/confirmations/" + confirmation + "/challenge"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return new Challenge(JsonPath.read(response, "$.nonce"), JsonPath.read(response, "$.payload"));
	}

	private ResultActions approve(String bearer, long confirmation, long deviceId, String nonce,
			Map<String, String> assertion) throws Exception {
		var body = new StringBuilder("{\"trustedDeviceId\": " + deviceId + ", \"nonce\": \"" + nonce + "\"");
		assertion.forEach((k, v) -> body.append(", \"").append(k).append("\": \"").append(v).append("\""));
		return as(bearer, post("/api/confirmations/" + confirmation + "/approve").contentType(MediaType.APPLICATION_JSON)
				.content(body.append("}").toString()));
	}

	private long requestUnlock(String bearer) throws Exception {
		String response = as(bearer, post("/api/devices/" + door.getId() + "/commands")
				.contentType(MediaType.APPLICATION_JSON).content("{\"state\": \"UNLOCK\"}"))
				.andExpect(jsonPath("$.status").value("confirmation_required"))
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(response, "$.confirmationId")).longValue();
	}

	private Person resident(String name) throws Exception {
		UUID identity = UUID.randomUUID();
		String email = name.toLowerCase() + suffix() + "@example.com";
		var claims = TestJwts.claims(identity, Map.of("email", email, "name", name, "auth_time", new Date())).build();
		var person = new Person(identity, email, name, TestJwts.sign(claims));
		long userId = id(as(person.jwt(), get("/api/me")).andReturn().getResponse().getContentAsString());
		as(ADMIN, put("/api/users/" + userId + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"roles\": [\"bewohner\"]}")).andExpect(status().isOk());
		return person;
	}

	private static String jwt(Person person, String authTime) {
		return TestJwts.sign(TestJwts.claims(person.identity(),
				Map.of("email", person.email(), "name", person.name(), "auth_time", authTime)).build());
	}

	private String aiToken() {
		long ki = users.findAll().stream().filter(u -> u.isMachine() && u.hasRole(Role.KI)).findFirst().orElseThrow()
				.getId();
		return tokens.create(ki, "webauthn test").secret();
	}

	private Device addDevice(String name) {
		var discovered = new DiscoveredDevice(RecordingDeviceAdapter.NAME, UUID.randomUUID().toString(), name, "lock",
				null, null, null, Map.of(), Map.of());
		var device = new Device(discovered, name, null);
		device.setLockedActions(List.of());
		return devices.save(device);
	}

	private long id(String json) {
		return ((Number) JsonPath.read(json, "$.id")).longValue();
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

	private static String suffix() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private static byte[] random(int length) {
		byte[] bytes = new byte[length];
		new SecureRandom().nextBytes(bytes);
		return bytes;
	}

	private static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String b64url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static byte[] fixed(BigInteger value) {
		byte[] bytes = value.toByteArray();
		byte[] out = new byte[32];
		int length = Math.min(bytes.length, 32);
		System.arraycopy(bytes, bytes.length - length, out, 32 - length, length);
		return out;
	}

}
