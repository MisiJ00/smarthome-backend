package dev.homeserver.smarthome.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import dev.homeserver.smarthome.RecordingDeviceAdapter;
import dev.homeserver.smarthome.TestJwts;
import dev.homeserver.smarthome.TestcontainersConfiguration;
import dev.homeserver.smarthome.token.ApiTokenService;
import dev.homeserver.smarthome.user.Role;
import dev.homeserver.smarthome.user.UserRepository;

/**
 * can() in the device API, for every role: who sees which devices and rooms, who may switch, configure and unlock.
 * Locked actions (the server's plug) hold for everyone, admins and the AI included.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DevicePermissionTest {

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

	long livingRoom;
	long office;
	Device lamp;
	Device officeLamp;
	Device serverPlug;
	Device frontDoor;

	@BeforeEach
	void home() throws Exception {
		livingRoom = createRoom("Wohnzimmer " + suffix());
		office = createRoom("Arbeitszimmer " + suffix());
		lamp = addDevice("Stehlampe", "light", livingRoom, List.of());
		officeLamp = addDevice("Schreibtischlampe", "light", office, List.of());
		serverPlug = addDevice("Server-Steckdose", "plug", office, List.of("switch_off"));
		frontDoor = addDevice("Haustür", "lock", null, List.of());
	}

	@Test
	void adminMayDoEverythingExceptLockedActions() throws Exception {
		as(ADMIN, get("/api/devices"))
				.andExpect(jsonPath("$[*].id", hasItems(id(lamp), id(officeLamp), id(serverPlug), id(frontDoor))));
		as(ADMIN, get("/api/devices/" + frontDoor.getId()))
				.andExpect(jsonPath("$.permissions[*].action", containsInAnyOrder("view", "control", "configure", "unlock")))
				.andExpect(jsonPath("$.permissions[?(@.action == 'unlock')].requiresConfirmation", contains(true)));

		command(ADMIN, lamp, "{\"state\": \"ON\"}").andExpect(jsonPath("$.status").value("sent"));
		command(ADMIN, serverPlug, "{\"state\": \"ON\"}").andExpect(jsonPath("$.status").value("sent"));
		command(ADMIN, serverPlug, "{\"state\": \"OFF\"}").andExpect(status().isForbidden());
		command(ADMIN, serverPlug, "{\"state\": \"TOGGLE\"}").andExpect(status().isForbidden());
		// Even admins only request an unlock
		command(ADMIN, frontDoor, "{\"state\": \"UNLOCK\"}")
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("confirmation_required"));
		command(ADMIN, frontDoor, "{\"state\": \"LOCK\"}").andExpect(jsonPath("$.status").value("sent"));

		assertThat(adapter.sentTo(serverPlug.getExternalId())).containsExactly(Map.of("state", "ON"));
		assertThat(adapter.sentTo(frontDoor.getExternalId())).containsExactly(Map.of("state", "LOCK"));

		rename(ADMIN, lamp, "Leselampe").andExpect(status().isOk());
		as(ADMIN, patch("/api/devices/" + lamp.getId()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"lockedActions\": [\"switch_off\"]}"))
				.andExpect(jsonPath("$.lockedActions", contains("switch_off")));
	}

	@Test
	void residentsSwitchButDoNotConfigure() throws Exception {
		String resident = personWithRoles("bewohner");

		as(resident, get("/api/devices"))
				.andExpect(jsonPath("$[*].id", hasItems(id(lamp), id(officeLamp), id(serverPlug), id(frontDoor))));
		as(resident, get("/api/devices/" + lamp.getId()))
				.andExpect(jsonPath("$.permissions[*].action", containsInAnyOrder("view", "control")));
		as(resident, get("/api/rooms")).andExpect(jsonPath("$[*].id", hasItems((int) livingRoom, (int) office)));

		command(resident, lamp, "{\"state\": \"ON\", \"brightness\": 80}").andExpect(jsonPath("$.status").value("sent"));
		command(resident, serverPlug, "{\"state\": \"OFF\"}").andExpect(status().isForbidden());
		command(resident, frontDoor, "{\"state\": \"UNLOCK\"}").andExpect(jsonPath("$.status").value("confirmation_required"));
		assertThat(adapter.sentTo(frontDoor.getExternalId())).isEmpty();

		rename(resident, lamp, "Meine Lampe").andExpect(status().isForbidden());
		as(resident, patch("/api/devices/" + lamp.getId()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"lockedActions\": []}"))
				.andExpect(status().isForbidden());
		as(resident, get("/api/devices/discovered")).andExpect(status().isForbidden());
		as(resident, post("/api/devices").contentType(MediaType.APPLICATION_JSON)
				.content("{\"adapter\": \"test\", \"externalId\": \"x\", \"name\": \"x\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void guestsOnlyLook() throws Exception {
		String guest = personWithRoles("gast");

		as(guest, get("/api/devices")).andExpect(jsonPath("$[*].id", hasItems(id(lamp), id(frontDoor))));
		as(guest, get("/api/devices/" + lamp.getId())).andExpect(jsonPath("$.permissions[*].action", contains("view")));
		command(guest, lamp, "{\"state\": \"ON\"}").andExpect(status().isForbidden());
		command(guest, frontDoor, "{\"state\": \"UNLOCK\"}").andExpect(status().isForbidden());
		assertThat(adapter.sentTo(lamp.getExternalId())).isEmpty();
	}

	@Test
	void aiSwitchesButOnlyRequestsUnlocking() throws Exception {
		String ai = aiToken();

		as(ai, get("/api/devices")).andExpect(jsonPath("$[*].id", hasItems(id(lamp), id(frontDoor))));
		command(ai, lamp, "{\"state\": \"OFF\"}").andExpect(jsonPath("$.status").value("sent"));
		command(ai, serverPlug, "{\"state\": \"OFF\"}").andExpect(status().isForbidden());
		command(ai, frontDoor, "{\"state\": \"UNLOCK\"}").andExpect(jsonPath("$.status").value("confirmation_required"));
		rename(ai, lamp, "KI-Lampe").andExpect(status().isForbidden());
		assertThat(adapter.sentTo(lamp.getExternalId())).containsExactly(Map.of("state", "OFF"));
	}

	@Test
	void roomGrantsOnlyShowThatRoom() throws Exception {
		String token = personWithRoles();
		long user = userId(token);
		grant(user, "room", livingRoom, "control", false);
		grant(user, "room", livingRoom, "configure", false);

		as(token, get("/api/devices"))
				.andExpect(jsonPath("$[*].id", contains(id(lamp))));
		as(token, get("/api/devices").param("roomId", String.valueOf(office))).andExpect(jsonPath("$[*].id").isEmpty());
		as(token, get("/api/devices/" + officeLamp.getId())).andExpect(status().isNotFound());
		as(token, get("/api/rooms"))
				.andExpect(jsonPath("$[*].id", contains((int) livingRoom)))
				.andExpect(jsonPath("$[0].deviceCount").value(1));
		as(token, get("/api/rooms/" + office)).andExpect(status().isNotFound());

		command(token, lamp, "{\"state\": \"ON\"}").andExpect(jsonPath("$.status").value("sent"));
		command(token, officeLamp, "{\"state\": \"ON\"}").andExpect(status().isNotFound());
		rename(token, lamp, "Ecklampe").andExpect(jsonPath("$.name").value("Ecklampe"));
		// Moving the lamp out of the room ends the access that came with the room
		as(token, patch("/api/devices/" + lamp.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"roomId\": null}"))
				.andExpect(status().isOk());
		as(token, get("/api/devices/" + lamp.getId())).andExpect(status().isNotFound());
	}

	@Test
	void grantsWithConfirmationDoNotSendDirectly() throws Exception {
		String token = personWithRoles();
		long user = userId(token);
		grant(user, "device", lamp.getId(), "control", true);

		command(token, lamp, "{\"state\": \"ON\"}").andExpect(jsonPath("$.status").value("confirmation_required"));
		assertThat(adapter.sentTo(lamp.getExternalId())).isEmpty();

		// A second grant without confirmation is more generous and wins
		grant(user, "room", livingRoom, "control", false);
		command(token, lamp, "{\"state\": \"ON\"}").andExpect(jsonPath("$.status").value("sent"));
		as(token, get("/api/devices")).andExpect(jsonPath("$[*].id", not(hasItem(id(frontDoor)))));
	}

	private ResultActions command(String bearer, Device device, String json) throws Exception {
		return as(bearer, post("/api/devices/" + device.getId() + "/commands").contentType(MediaType.APPLICATION_JSON)
				.content(json));
	}

	private ResultActions rename(String bearer, Device device, String name) throws Exception {
		return as(bearer, patch("/api/devices/" + device.getId()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"" + name + "\"}"));
	}

	/** A signed-in person with exactly these roles. */
	private String personWithRoles(String... roles) throws Exception {
		String token = TestJwts.token(UUID.randomUUID(), "p" + suffix() + "@example.com", "Person");
		long id = userId(token);
		String names = String.join("\", \"", roles);
		as(ADMIN, put("/api/users/" + id + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content(roles.length == 0 ? "{\"roles\": []}" : "{\"roles\": [\"" + names + "\"]}"))
				.andExpect(status().isOk());
		return token;
	}

	private void grant(long user, String scopeType, long scopeId, String action, boolean confirm) throws Exception {
		as(ADMIN, post("/api/grants").contentType(MediaType.APPLICATION_JSON)
				.content("{\"subjectType\": \"user\", \"subjectId\": " + user + ", \"scopeType\": \"" + scopeType
						+ "\", \"scopeId\": " + scopeId + ", \"action\": \"" + action + "\", \"requiresConfirmation\": "
						+ confirm + "}"))
				.andExpect(status().isCreated());
	}

	private long userId(String token) throws Exception {
		String me = as(token, get("/api/me")).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(me, "$.id")).longValue();
	}

	private String aiToken() {
		long ki = users.findAll().stream().filter(u -> u.isMachine() && u.hasRole(Role.KI)).findFirst().orElseThrow().getId();
		return tokens.create(ki, "permission test").secret();
	}

	private long createRoom(String name) throws Exception {
		String response = as(ADMIN, post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"" + name + "\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(response, "$.id")).longValue();
	}

	private Device addDevice(String name, String type, Long roomId, List<String> lockedActions) {
		var discovered = new DiscoveredDevice(RecordingDeviceAdapter.NAME, UUID.randomUUID().toString(), name, type, null,
				null, null, Map.of(), Map.of());
		var device = new Device(discovered, name, roomId);
		device.setLockedActions(lockedActions);
		return devices.save(device);
	}

	private static int id(Device device) {
		return device.getId().intValue();
	}

	private static String suffix() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

}
