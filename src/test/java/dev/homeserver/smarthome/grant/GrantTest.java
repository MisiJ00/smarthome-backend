package dev.homeserver.smarthome.grant;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

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

import dev.homeserver.smarthome.TestJwts;
import dev.homeserver.smarthome.TestcontainersConfiguration;
import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.device.DiscoveredDevice;
import dev.homeserver.smarthome.user.RoleRepository;

/** Grants: admins hand out permissions to roles and users; effective permissions show what that means per device. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GrantTest {

	private static final String ADMIN = TestJwts.token(UUID.randomUUID(), "admin@example.com", "Admin");

	@Autowired
	MockMvc mvc;

	@Autowired
	DeviceRepository devices;

	@Autowired
	RoleRepository roles;

	@Test
	void defaultGrantsForTheSystemRoles() throws Exception {
		long bewohner = roles.findByName("bewohner").orElseThrow().getId();
		long gast = roles.findByName("gast").orElseThrow().getId();
		long ki = roles.findByName("ki").orElseThrow().getId();

		as(ADMIN, get("/api/grants").param("subjectType", "role").param("subjectId", String.valueOf(bewohner)))
				.andExpect(jsonPath("$[*].action", containsInAnyOrder("view", "control", "unlock")))
				.andExpect(jsonPath("$[*].scopeType", everyItem(is("all"))))
				.andExpect(jsonPath("$[?(@.action == 'unlock')].requiresConfirmation", contains(true)))
				.andExpect(jsonPath("$[0].subjectName").value("bewohner"));
		as(ADMIN, get("/api/grants").param("subjectType", "role").param("subjectId", String.valueOf(gast)))
				.andExpect(jsonPath("$[*].action", contains("view")));
		as(ADMIN, get("/api/grants").param("subjectType", "role").param("subjectId", String.valueOf(ki)))
				.andExpect(jsonPath("$[*].action", containsInAnyOrder("view", "control", "unlock")));
		as(ADMIN, get("/api/grants").param("subjectType", "nobody")).andExpect(status().isBadRequest());
	}

	@Test
	void createsChangesAndDeletesGrants() throws Exception {
		long user = userId(TestJwts.token(UUID.randomUUID(), "erika" + suffix() + "@example.com", "Erika"));
		long room = createRoom("Kinderzimmer " + suffix());
		long lock = addDevice("Haustür", "lock");

		String created = grant("user", user, "room", room, "configure", null)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.subjectName").value("Erika"))
				.andExpect(jsonPath("$.scopeId").value(room))
				.andExpect(jsonPath("$.requiresConfirmation").value(false))
				.andReturn().getResponse().getContentAsString();
		int grantId = JsonPath.read(created, "$.id");
		grant("user", user, "room", room, "configure", null).andExpect(status().isConflict());

		// Unlocking always needs a confirmation
		String unlock = grant("user", user, "device", lock, "unlock", false)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.requiresConfirmation").value(true))
				.andReturn().getResponse().getContentAsString();
		int unlockId = JsonPath.read(unlock, "$.id");
		as(ADMIN, patch("/api/grants/" + unlockId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"requiresConfirmation\": false}"))
				.andExpect(status().isBadRequest());
		as(ADMIN, patch("/api/grants/" + grantId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"requiresConfirmation\": true}"))
				.andExpect(jsonPath("$.requiresConfirmation").value(true));

		// Invalid subjects and scopes
		grant("user", 999999, "all", null, "view", null).andExpect(status().isBadRequest());
		grant("user", user, "room", 999999L, "view", null).andExpect(status().isBadRequest());
		grant("user", user, "all", (long) room, "view", null).andExpect(status().isBadRequest());
		as(ADMIN, post("/api/grants").contentType(MediaType.APPLICATION_JSON)
				.content("{\"subjectType\": \"user\", \"subjectId\": " + user + ", \"scopeType\": \"all\", \"action\": \"fly\"}"))
				.andExpect(status().isBadRequest());

		// Filters
		as(ADMIN, get("/api/grants").param("subjectType", "user").param("subjectId", String.valueOf(user)))
				.andExpect(jsonPath("$[*].id", containsInAnyOrder(grantId, unlockId)));
		as(ADMIN, get("/api/grants").param("scopeType", "device").param("scopeId", String.valueOf(lock)))
				.andExpect(jsonPath("$[*].id", contains(unlockId)));
		as(ADMIN, get("/api/users/" + user)).andExpect(jsonPath("$.grants[*].id", containsInAnyOrder(grantId, unlockId)));

		as(ADMIN, delete("/api/grants/" + grantId)).andExpect(status().isNoContent());
		as(ADMIN, get("/api/grants/" + grantId)).andExpect(status().isNotFound());
	}

	@Test
	void grantsDisappearWithTheirRoomOrDevice() throws Exception {
		long user = userId(TestJwts.token(UUID.randomUUID(), "anna" + suffix() + "@example.com", "Anna"));
		long room = createRoom("Werkstatt " + suffix());
		long device = addDevice("Bohrmaschine", "plug");
		int roomGrant = JsonPath.read(grant("user", user, "room", room, "control", null).andReturn().getResponse()
				.getContentAsString(), "$.id");
		int deviceGrant = JsonPath.read(grant("user", user, "device", device, "control", null).andReturn().getResponse()
				.getContentAsString(), "$.id");

		as(ADMIN, delete("/api/rooms/" + room)).andExpect(status().isNoContent());
		as(ADMIN, get("/api/grants/" + roomGrant)).andExpect(status().isNotFound());

		as(ADMIN, delete("/api/devices/" + device)).andExpect(status().isNoContent());
		as(ADMIN, get("/api/grants/" + deviceGrant)).andExpect(status().isNotFound());
	}

	@Test
	void effectivePermissionsCombineRoleAndOwnGrants() throws Exception {
		String token = TestJwts.token(UUID.randomUUID(), "gast" + suffix() + "@example.com", "Gast");
		long user = userId(token);
		as(ADMIN, put("/api/users/" + user + "/roles")
				.contentType(MediaType.APPLICATION_JSON).content("{\"roles\": [\"gast\"]}"))
				.andExpect(status().isOk());
		long room = createRoom("Gästebad " + suffix());
		long light = addDevice("Spiegellicht", "light");
		long lock = addDevice("Gartentor", "lock");
		moveDevice(light, room);
		grant("user", user, "room", room, "control", null).andExpect(status().isCreated());
		grant("user", user, "device", lock, "unlock", null).andExpect(status().isCreated());

		as(ADMIN, get("/api/users/" + user + "/effective-permissions"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.admin").value(false))
				.andExpect(jsonPath("$.roles", contains("gast")))
				// Role grant (gast: view on all) plus the two own grants
				.andExpect(jsonPath("$.grants[*].subjectType", containsInAnyOrder("role", "user", "user")))
				// Light in the room: view from the role, control from the room grant
				.andExpect(jsonPath("$.devices[?(@.deviceId == " + light + ")].permissions[*].action",
						containsInAnyOrder("view", "control")))
				// Lock: view from the role, unlock only with confirmation
				.andExpect(jsonPath("$.devices[?(@.deviceId == " + lock + ")].permissions[?(@.action == 'unlock')].requiresConfirmation",
						contains(true)))
				.andExpect(jsonPath("$.devices[?(@.deviceId == " + lock + ")].permissions[*].action",
						not(hasItem("control"))));

		// Admins may do everything without grants; unlock only exists for locks
		long admin = userId(ADMIN);
		as(ADMIN, get("/api/users/" + admin + "/effective-permissions"))
				.andExpect(jsonPath("$.admin").value(true))
				.andExpect(jsonPath("$.devices[?(@.deviceId == " + light + ")].permissions[*].action",
						containsInAnyOrder("view", "control", "configure")))
				.andExpect(jsonPath("$.devices[?(@.deviceId == " + lock + ")].permissions[*].action",
						containsInAnyOrder("view", "control", "configure", "unlock")));

		// Without roles and grants: nothing
		String nobody = TestJwts.token(UUID.randomUUID(), "niemand" + suffix() + "@example.com", "Niemand");
		as(ADMIN, get("/api/users/" + userId(nobody) + "/effective-permissions"))
				.andExpect(jsonPath("$.devices", empty()))
				.andExpect(jsonPath("$.grants", empty()));
	}

	private ResultActions grant(String subjectType, long subjectId, String scopeType, Long scopeId, String action,
			Boolean requiresConfirmation) throws Exception {
		String body = "{\"subjectType\": \"" + subjectType + "\", \"subjectId\": " + subjectId + ", \"scopeType\": \""
				+ scopeType + "\", \"scopeId\": " + scopeId + ", \"action\": \"" + action + "\", \"requiresConfirmation\": "
				+ requiresConfirmation + "}";
		return as(ADMIN, post("/api/grants").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private long userId(String token) throws Exception {
		String me = as(token, get("/api/me")).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(me, "$.id")).longValue();
	}

	private long createRoom(String name) throws Exception {
		String response = as(ADMIN, post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"" + name + "\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(response, "$.id")).longValue();
	}

	private long addDevice(String name, String type) {
		var discovered = new DiscoveredDevice("sim", UUID.randomUUID().toString(), name, type, null, null, null,
				Map.of(), Map.of());
		return devices.save(new Device(discovered, name, null)).getId();
	}

	private void moveDevice(long device, long room) throws Exception {
		as(ADMIN, patch("/api/devices/" + device).contentType(MediaType.APPLICATION_JSON)
				.content("{\"roomId\": " + room + "}"))
				.andExpect(status().isOk());
	}

	private static String suffix() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

}
