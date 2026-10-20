package dev.homeserver.smarthome.trusteddevice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
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
 * Approving with a phone: the phone's key signs a one-time challenge, so a person can open the door on their own.
 * Wrong, foreign, revoked or replayed signatures are refused, and the AI cannot register a phone.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TrustedDeviceTest {

	private static final String ADMIN = TestJwts.token(UUID.randomUUID(), "admin@example.com", "Admin");

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
	Person max;

	/** A resident with a JWT from a login that just happened. */
	record Person(UUID identity, String email, String name, String jwt) {
	}

	@BeforeEach
	void home() throws Exception {
		door = addDevice("Haustür " + suffix());
		erika = resident("Erika");
		max = resident("Max");
	}

	@Test
	void residentOpensTheDoorAloneWithTheirPhone() throws Exception {
		KeyPair phone = keyPair();
		long phoneId = id(register(erika.jwt(), "Erikas iPhone", spki(phone))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.active").value(true))
				.andReturn().getResponse().getContentAsString());
		as(erika.jwt(), get("/api/me/trusted-devices")).andExpect(jsonPath("$[*].name", contains("Erikas iPhone")));

		long confirmation = requestUnlock(erika.jwt());
		as(erika.jwt(), get("/api/confirmations/" + confirmation))
				.andExpect(jsonPath("$.canDecide").value(false))
				.andExpect(jsonPath("$.canApproveWithPhone").value(true));
		// Without a signature it stays forbidden
		as(erika.jwt(), post("/api/confirmations/" + confirmation + "/approve")).andExpect(status().isForbidden());

		var challenge = challenge(erika.jwt(), confirmation);
		assertThat(challenge.payload()).isEqualTo("smarthome-approval:v1\n" + confirmation + "\nunlock\n"
				+ door.getId() + "\n" + challenge.nonce());
		approve(erika.jwt(), confirmation, phoneId, challenge.nonce(), sign(phone, challenge.payload()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("approved"))
				.andExpect(jsonPath("$.decidedByName").value("Erika"));
		assertThat(adapter.sentTo(door.getExternalId())).containsExactly(Map.of("state", "UNLOCK"));

		as(ADMIN, get("/api/audit").param("deviceId", String.valueOf(door.getId())).param("result", "approved"))
				.andExpect(jsonPath("$.items[0].params.approvedWith").value("Erikas iPhone"))
				.andExpect(jsonPath("$.items[0].userName").value("Erika"));
		as(erika.jwt(), get("/api/me/trusted-devices")).andExpect(jsonPath("$[0].lastUsed").exists());
	}

	@Test
	void wrongForeignRevokedAndReplayedSignaturesAreRefused() throws Exception {
		KeyPair phone = keyPair();
		long phoneId = id(register(erika.jwt(), "Pixel", spki(phone)).andReturn().getResponse().getContentAsString());
		KeyPair maxPhone = keyPair();
		long maxPhoneId = id(register(max.jwt(), "Max Handy", spki(maxPhone)).andReturn().getResponse().getContentAsString());
		long confirmation = requestUnlock(erika.jwt());

		// Signed with another key
		var first = challenge(erika.jwt(), confirmation);
		approve(erika.jwt(), confirmation, phoneId, first.nonce(), sign(keyPair(), first.payload()))
				.andExpect(status().isForbidden());
		// The nonce is used up by that attempt, even with the right signature now
		approve(erika.jwt(), confirmation, phoneId, first.nonce(), sign(phone, first.payload()))
				.andExpect(status().isForbidden());

		// Someone else's phone, and a nonce issued to someone else
		var second = challenge(erika.jwt(), confirmation);
		approve(erika.jwt(), confirmation, maxPhoneId, second.nonce(), sign(maxPhone, second.payload()))
				.andExpect(status().isForbidden());
		var maxChallenge = challenge(max.jwt(), confirmation);
		approve(erika.jwt(), confirmation, phoneId, maxChallenge.nonce(), sign(phone, maxChallenge.payload()))
				.andExpect(status().isForbidden());

		// Signature for a different text (other confirmation id)
		var third = challenge(erika.jwt(), confirmation);
		approve(erika.jwt(), confirmation, phoneId, third.nonce(),
				sign(phone, third.payload().replace("\n" + confirmation + "\n", "\n999999\n")))
				.andExpect(status().isForbidden());
		assertThat(adapter.sentTo(door.getExternalId())).isEmpty();

		// Revoked phone
		as(erika.jwt(), delete("/api/me/trusted-devices/" + phoneId)).andExpect(status().isNoContent());
		as(erika.jwt(), post("/api/confirmations/" + confirmation + "/challenge")).andExpect(status().isConflict());
		long newPhone = id(register(erika.jwt(), "Neues Handy", spki(keyPair())).andReturn().getResponse()
				.getContentAsString());
		var fourth = challenge(erika.jwt(), confirmation);
		approve(erika.jwt(), confirmation, phoneId, fourth.nonce(), sign(phone, fourth.payload()))
				.andExpect(status().isForbidden());
		assertThat(newPhone).isNotEqualTo(phoneId);

		// Max may decide anyway, and with his phone it works too; replaying his approval does not
		var maxSecond = challenge(max.jwt(), confirmation);
		String signature = sign(maxPhone, maxSecond.payload());
		approve(max.jwt(), confirmation, maxPhoneId, maxSecond.nonce(), signature).andExpect(status().isOk());
		approve(max.jwt(), confirmation, maxPhoneId, maxSecond.nonce(), signature).andExpect(status().isForbidden());
		assertThat(adapter.sentTo(door.getExternalId())).containsExactly(Map.of("state", "UNLOCK"));
	}

	@Test
	void registeringNeedsAFreshLoginAndAPerson() throws Exception {
		KeyPair phone = keyPair();
		Instant hourAgo = Instant.now().minusSeconds(3600);
		String stale = jwt(erika, hourAgo.toString());
		register(stale, "Altes Login", spki(phone))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.reason").value("fresh_login_required"));
		register(TestJwts.token(erika.identity(), erika.email(), erika.name()), "Ohne auth_time", spki(phone))
				.andExpect(status().isForbidden());

		// The AI never gets a phone
		register(aiToken(), "KI", spki(phone)).andExpect(status().isForbidden());

		// Kratos sends auth_time as RFC 3339 text, other issuers as seconds; both count
		register(jwt(erika, Instant.now().toString()), "iPhone", raw(phone)).andExpect(status().isCreated());
		register(erika.jwt(), "Nochmal", spki(phone)).andExpect(status().isConflict());

		register(erika.jwt(), "Kaputt", "bm9wZQ==").andExpect(status().isBadRequest());
		register(erika.jwt(), "P-384", spki(keyPair("secp384r1"))).andExpect(status().isBadRequest());
		register(erika.jwt(), " ", spki(keyPair())).andExpect(status().isBadRequest());
	}

	@Test
	void adminsSeeAndRevokePhonesOfAnyUser() throws Exception {
		long phoneId = id(register(erika.jwt(), "Verloren", spki(keyPair())).andReturn().getResponse()
				.getContentAsString());
		long erikaId = userId(erika.jwt());

		as(max.jwt(), get("/api/users/" + erikaId + "/trusted-devices")).andExpect(status().isForbidden());
		as(max.jwt(), delete("/api/me/trusted-devices/" + phoneId)).andExpect(status().isNotFound());

		as(ADMIN, get("/api/users/" + erikaId + "/trusted-devices"))
				.andExpect(jsonPath("$[*].name", contains("Verloren")));
		as(ADMIN, delete("/api/users/" + erikaId + "/trusted-devices/" + phoneId)).andExpect(status().isNoContent());
		as(erika.jwt(), get("/api/me/trusted-devices"))
				.andExpect(jsonPath("$[0].active").value(false))
				.andExpect(jsonPath("$[0].revokedAt").exists());
		as(ADMIN, get("/api/audit").param("userId", String.valueOf(userId(ADMIN))).param("result", "revoked"))
				.andExpect(jsonPath("$.items[0].params.name").value("Verloren"));
	}

	record Challenge(String nonce, String payload) {
	}

	private Challenge challenge(String bearer, long confirmation) throws Exception {
		String response = as(bearer, post("/api/confirmations/" + confirmation + "/challenge"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return new Challenge(JsonPath.read(response, "$.nonce"), JsonPath.read(response, "$.payload"));
	}

	private ResultActions approve(String bearer, long confirmation, long phoneId, String nonce, String signature)
			throws Exception {
		return as(bearer, post("/api/confirmations/" + confirmation + "/approve").contentType(MediaType.APPLICATION_JSON)
				.content("{\"trustedDeviceId\": " + phoneId + ", \"nonce\": \"" + nonce + "\", \"signature\": \""
						+ signature + "\"}"));
	}

	private ResultActions register(String bearer, String name, String publicKey) throws Exception {
		return as(bearer, post("/api/me/trusted-devices").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"" + name + "\", \"publicKey\": \"" + publicKey + "\"}"));
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
		as(ADMIN, put("/api/users/" + userId(person.jwt()) + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"roles\": [\"bewohner\"]}"))
				.andExpect(status().isOk());
		return person;
	}

	private static String jwt(Person person, String authTime) {
		return TestJwts.sign(TestJwts.claims(person.identity(),
				Map.of("email", person.email(), "name", person.name(), "auth_time", authTime)).build());
	}

	private static KeyPair keyPair() throws Exception {
		return keyPair("secp256r1");
	}

	private static KeyPair keyPair(String curve) throws Exception {
		var generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec(curve));
		return generator.generateKeyPair();
	}

	/** Like Android Keystore exports it. */
	private static String spki(KeyPair keyPair) {
		return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
	}

	/** Like iOS exports it: 04 || X || Y. */
	private static String raw(KeyPair keyPair) {
		var point = ((ECPublicKey) keyPair.getPublic()).getW();
		byte[] out = new byte[65];
		out[0] = 0x04;
		copy(point.getAffineX(), out, 1);
		copy(point.getAffineY(), out, 33);
		return Base64.getEncoder().encodeToString(out);
	}

	private static void copy(BigInteger value, byte[] out, int offset) {
		byte[] bytes = value.toByteArray();
		int length = Math.min(bytes.length, 32);
		System.arraycopy(bytes, bytes.length - length, out, offset + 32 - length, length);
	}

	private static String sign(KeyPair keyPair, String payload) throws Exception {
		var signer = Signature.getInstance("SHA256withECDSA");
		signer.initSign(keyPair.getPrivate());
		signer.update(payload.getBytes(StandardCharsets.UTF_8));
		return Base64.getEncoder().encodeToString(signer.sign());
	}

	private long id(String json) {
		return ((Number) JsonPath.read(json, "$.id")).longValue();
	}

	private long userId(String token) throws Exception {
		return id(as(token, get("/api/me")).andReturn().getResponse().getContentAsString());
	}

	private String aiToken() {
		long ki = users.findAll().stream().filter(u -> u.isMachine() && u.hasRole(Role.KI)).findFirst().orElseThrow().getId();
		return tokens.create(ki, "trusted device test").secret();
	}

	private Device addDevice(String name) {
		var discovered = new DiscoveredDevice(RecordingDeviceAdapter.NAME, UUID.randomUUID().toString(), name, "lock",
				null, null, null, Map.of(), Map.of());
		var device = new Device(discovered, name, null);
		device.setLockedActions(List.of());
		return devices.save(device);
	}

	private static String suffix() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

}
