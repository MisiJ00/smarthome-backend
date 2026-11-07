package dev.homeserver.smarthome.confirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import dev.homeserver.smarthome.RecordingDeviceAdapter;
import dev.homeserver.smarthome.TestJwts;
import dev.homeserver.smarthome.TestcontainersConfiguration;
import dev.homeserver.smarthome.audit.Via;
import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.device.DiscoveredDevice;
import dev.homeserver.smarthome.token.ApiTokenService;
import dev.homeserver.smarthome.user.Role;
import dev.homeserver.smarthome.user.UserRepository;

/**
 * Unlocking the door: the request waits until another person approves it, is rejected, or expires. Everything ends up
 * in the audit log.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ConfirmationAuditTest {

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

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ConfirmationService confirmations;

	Device door;
	String erika;
	String max;

	@BeforeEach
	void home() throws Exception {
		door = addDevice("Haustür " + suffix(), "lock", List.of());
		erika = personWithRoles("Erika", "bewohner");
		max = personWithRoles("Max", "bewohner");
	}

	@Test
	void aPersonsRequestOnlyTheyApproveAndMayWithdraw() throws Exception {
		long id = requestUnlock(erika, "app");
		String approve = "/api/confirmations/" + id + "/approve";
		String reject = "/api/confirmations/" + id + "/reject";

		// Erika sees her request; without the signature of her phone she cannot approve it
		as(erika, get("/api/confirmations"))
				.andExpect(jsonPath("$[?(@.id == " + id + ")].mine", contains(true)))
				.andExpect(jsonPath("$[?(@.id == " + id + ")].canDecide", contains(false)));
		as(erika, post(approve)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("confirmation.own_request"));

		// Nobody else decides a person's request: other residents do not even see it, admins see it but may not
		as(max, get("/api/confirmations")).andExpect(jsonPath("$[*].id", not(hasItem((int) id))));
		as(max, post(approve)).andExpect(status().isNotFound());
		as(ADMIN, get("/api/confirmations/" + id))
				.andExpect(jsonPath("$.userName").value("Erika"))
				.andExpect(jsonPath("$.deviceName").value(door.getName()))
				.andExpect(jsonPath("$.action").value("unlock"))
				.andExpect(jsonPath("$.params.state").value("UNLOCK"))
				.andExpect(jsonPath("$.canDecide").value(false))
				.andExpect(jsonPath("$.canApproveWithPhone").value(false));
		as(ADMIN, post(approve)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("confirmation.person_request"));
		as(ADMIN, post(reject)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("confirmation.person_request"));
		as(ADMIN, post("/api/confirmations/" + id + "/challenge")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("confirmation.person_request"));
		// A signed approval of someone else's request fails as well (no challenge of theirs exists for it)
		as(ADMIN, post(approve).contentType(MediaType.APPLICATION_JSON)
				.content("{\"trustedDeviceId\": 1, \"nonce\": \"x\", \"signature\": \"x\"}"))
				.andExpect(status().isForbidden());

		// The AI and guests never decide, they do not even see it
		as(aiToken(), post(approve)).andExpect(status().isNotFound());
		String guest = personWithRoles("Gast", "gast");
		as(guest, get("/api/confirmations")).andExpect(jsonPath("$[*].id", not(hasItem((int) id))));
		as(guest, post(approve)).andExpect(status().isNotFound());
		assertThat(adapter.sentTo(door.getExternalId())).isEmpty();

		// Erika withdraws it (e.g. she cancelled the fingerprint): rejected by herself
		as(erika, post(reject).header(Via.HEADER, "app"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("rejected"))
				.andExpect(jsonPath("$.decidedByName").value("Erika"));
		as(erika, post(reject)).andExpect(status().isConflict());
		assertThat(adapter.sentTo(door.getExternalId())).isEmpty();

		// Audit: request and withdrawal, both by Erika over the app, newest first
		as(ADMIN, get("/api/audit").param("deviceId", String.valueOf(door.getId())))
				.andExpect(jsonPath("$.total").value(2))
				.andExpect(jsonPath("$.items[*].result", contains("rejected", "pending")))
				.andExpect(jsonPath("$.items[*].userName", contains("Erika", "Erika")))
				.andExpect(jsonPath("$.items[*].via", contains("app", "app")))
				.andExpect(jsonPath("$.items[*].confirmationId", contains((int) id, (int) id)))
				.andExpect(jsonPath("$.items[0].params.requestedBy").value("Erika"))
				.andExpect(jsonPath("$.items[0].params.withdrawn").value(true));
	}

	@Test
	void aiOnlyRequestsAndAPersonDecides() throws Exception {
		String ai = aiToken();
		String response = command(ai, null, "{\"state\": \"UNLOCK\"}")
				.andExpect(jsonPath("$.status").value("confirmation_required"))
				.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(response, "$.confirmationId")).longValue();

		as(ai, post("/api/confirmations/" + id + "/approve")).andExpect(status().isForbidden());
		as(erika, post("/api/confirmations/" + id + "/reject"))
				.andExpect(jsonPath("$.status").value("rejected"));
		assertThat(adapter.sentTo(door.getExternalId())).isEmpty();

		as(ADMIN, get("/api/audit").param("deviceId", String.valueOf(door.getId())))
				.andExpect(jsonPath("$.items[*].result", contains("rejected", "pending")))
				.andExpect(jsonPath("$.items[*].via", contains("app", "ki")));
		as(ADMIN, get("/api/audit").param("deviceId", String.valueOf(door.getId())).param("via", "ki"))
				.andExpect(jsonPath("$.total").value(1));
	}

	@Test
	void aDeactivatedMachineCannotHaveItsRequestApproved() throws Exception {
		long id = requestUnlock(aiToken(), null);
		long ki = users.findAll().stream().filter(u -> u.isMachine() && u.hasRole(Role.KI)).findFirst().orElseThrow().getId();
		jdbc.update("UPDATE users SET is_active = FALSE WHERE id = ?", ki);
		try {
			as(max, post("/api/confirmations/" + id + "/approve")).andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("confirmation.requester_inactive"));
			assertThat(adapter.sentTo(door.getExternalId())).isEmpty();
			as(max, get("/api/confirmations/" + id)).andExpect(jsonPath("$.status").value("pending"));
		}
		finally {
			jdbc.update("UPDATE users SET is_active = TRUE WHERE id = ?", ki);
		}
	}

	@Test
	void unansweredRequestsExpire() throws Exception {
		long id = requestUnlock(erika, null);
		jdbc.update("UPDATE confirmations SET expires_at = now() - interval '1 minute' WHERE id = ?", id);
		confirmations.expireDue();

		as(ADMIN, post("/api/confirmations/" + id + "/approve")).andExpect(status().isConflict());
		as(erika, get("/api/confirmations/" + id)).andExpect(jsonPath("$.status").value("expired"));
		as(ADMIN, get("/api/audit").param("deviceId", String.valueOf(door.getId())).param("result", "expired"))
				.andExpect(jsonPath("$.items[*].via", contains("system")))
				.andExpect(jsonPath("$.items[*].userName", contains("Erika")));
		assertThat(adapter.sentTo(door.getExternalId())).isEmpty();
	}

	@Test
	void lockingTheActionInTheMeantimeBlocksTheApproval() throws Exception {
		// a request of the AI, so that another person decides
		long id = requestUnlock(aiToken(), null);
		as(ADMIN, patch("/api/devices/" + door.getId()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"lockedActions\": [\"unlock\"]}"))
				.andExpect(status().isOk());

		as(max, post("/api/confirmations/" + id + "/approve")).andExpect(status().isForbidden());
		assertThat(adapter.sentTo(door.getExternalId())).isEmpty();
		as(max, get("/api/confirmations/" + id)).andExpect(jsonPath("$.status").value("pending"));
	}

	@Test
	void auditLogRecordsRefusalsAndIsAdminOnly() throws Exception {
		Device plug = addDevice("Server-Steckdose " + suffix(), "plug", List.of("switch_off"));
		String guest = personWithRoles("Gast", "gast");

		as(guest, post("/api/devices/" + plug.getId() + "/commands").contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\": \"ON\"}"))
				.andExpect(status().isForbidden());
		as(erika, post("/api/devices/" + plug.getId() + "/commands").contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\": \"OFF\"}"))
				.andExpect(status().isForbidden());
		as(erika, post("/api/devices/" + plug.getId() + "/commands").header(Via.HEADER, "widget")
				.contentType(MediaType.APPLICATION_JSON).content("{\"state\": \"ON\"}"))
				.andExpect(jsonPath("$.status").value("sent"));

		as(ADMIN, get("/api/audit").param("deviceId", String.valueOf(plug.getId())))
				.andExpect(jsonPath("$.items[*].result", contains("sent", "locked", "denied")))
				.andExpect(jsonPath("$.items[0].via").value("widget"))
				.andExpect(jsonPath("$.items[0].action").value("control"))
				.andExpect(jsonPath("$.items[0].params.state").value("ON"));
		as(ADMIN, get("/api/audit").param("deviceId", String.valueOf(plug.getId())).param("size", "1").param("page", "1"))
				.andExpect(jsonPath("$.items[*].result", contains("locked")))
				.andExpect(jsonPath("$.total").value(3));
		as(ADMIN, get("/api/audit").param("size", "500")).andExpect(status().isBadRequest());
		as(ADMIN, get("/api/audit").param("from", "2100-01-01T00:00:00Z")).andExpect(jsonPath("$.total").value(0));

		as(erika, get("/api/audit")).andExpect(status().isForbidden());
		as(aiToken(), get("/api/audit")).andExpect(status().isForbidden());
	}

	private long requestUnlock(String bearer, String client) throws Exception {
		String response = command(bearer, client, "{\"state\": \"UNLOCK\"}")
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("confirmation_required"))
				.andExpect(jsonPath("$.expiresAt").exists())
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(response, "$.confirmationId")).longValue();
	}

	private ResultActions command(String bearer, String client, String json) throws Exception {
		var request = post("/api/devices/" + door.getId() + "/commands").contentType(MediaType.APPLICATION_JSON).content(json);
		if (client != null) {
			request.header(Via.HEADER, client);
		}
		return as(bearer, request);
	}

	private String personWithRoles(String name, String... roles) throws Exception {
		String token = TestJwts.token(UUID.randomUUID(), "p" + suffix() + "@example.com", name);
		String me = as(token, get("/api/me")).andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(me, "$.id")).longValue();
		as(ADMIN, put("/api/users/" + id + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"roles\": [\"" + String.join("\", \"", roles) + "\"]}"))
				.andExpect(status().isOk());
		return token;
	}

	private String aiToken() {
		long ki = users.findAll().stream().filter(u -> u.isMachine() && u.hasRole(Role.KI)).findFirst().orElseThrow().getId();
		return tokens.create(ki, "confirmation test").secret();
	}

	private Device addDevice(String name, String type, List<String> lockedActions) {
		var discovered = new DiscoveredDevice(RecordingDeviceAdapter.NAME, UUID.randomUUID().toString(), name, type, null,
				null, null, Map.of(), Map.of());
		var device = new Device(discovered, name, null);
		device.setLockedActions(lockedActions);
		return devices.save(device);
	}

	private static String suffix() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

}
