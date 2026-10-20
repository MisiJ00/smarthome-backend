package dev.homeserver.smarthome.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
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
import dev.homeserver.smarthome.token.ApiTokenService;
import dev.homeserver.smarthome.user.UserRepository;

/** Rooms: admins manage them, everyone signed in reads them, devices can be put in a room or none. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomApiTest {

	private static final String ADMIN = TestJwts.token(UUID.randomUUID(), "admin@example.com", "Admin");
	private static final String RESIDENT = TestJwts.token(UUID.randomUUID(), "bewohner@example.com", "Erika");

	@Autowired
	MockMvc mvc;

	@Autowired
	DeviceRepository devices;

	@Autowired
	UserRepository users;

	@Autowired
	ApiTokenService tokens;

	@Test
	void adminCreatesRoomAndNamesAreUniqueIgnoringCase() throws Exception {
		String name = unique("Küche");

		long id = createRoom("  " + name + "  ", "kitchen");
		as(ADMIN, get("/api/rooms/" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value(name))
				.andExpect(jsonPath("$.icon").value("kitchen"))
				.andExpect(jsonPath("$.deviceCount").value(0));
		as(ADMIN, get("/api/rooms")).andExpect(jsonPath("$[*].name", hasItem(name)));

		as(ADMIN, post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"" + name.toUpperCase() + "\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409));
		as(ADMIN, post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"   \"}"))
				.andExpect(status().isBadRequest());
		as(ADMIN, post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + "x".repeat(65) + "\"}"))
				.andExpect(status().isBadRequest());
		as(ADMIN, get("/api/rooms/999999")).andExpect(status().isNotFound());
	}

	@Test
	void residentsAndAiReadButCannotChangeRooms() throws Exception {
		long id = createRoom(unique("Bad"), null);
		moveDevice(addDevice("Badlicht"), String.valueOf(id)).andExpect(status().isOk());
		String ai = aiToken();

		// The AI may view all devices, so it sees the room; the resident has no roles and sees nothing
		as(ai, get("/api/rooms/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.deviceCount").value(1));
		as(RESIDENT, get("/api/rooms/" + id)).andExpect(status().isNotFound());
		as(RESIDENT, get("/api/rooms")).andExpect(jsonPath("$[*].id", not(hasItem((int) id))));

		for (String bearer : List.of(RESIDENT, ai)) {
			as(bearer, get("/api/rooms")).andExpect(status().isOk());
			as(bearer, post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Keller\"}"))
					.andExpect(status().isForbidden());
			as(bearer, patch("/api/rooms/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Keller\"}"))
					.andExpect(status().isForbidden());
			as(bearer, put("/api/rooms/order").contentType(MediaType.APPLICATION_JSON).content("{\"roomIds\": []}"))
					.andExpect(status().isForbidden());
			as(bearer, delete("/api/rooms/" + id)).andExpect(status().isForbidden());
		}
		mvc.perform(get("/api/rooms")).andExpect(status().isUnauthorized());
	}

	@Test
	void renamesToOwnNameButNotToAnotherRoomsName() throws Exception {
		String name = unique("Flur");
		String other = unique("Büro");
		long id = createRoom(name, "door");
		createRoom(other, null);

		// Same name in a different spelling is fine for the room itself
		as(ADMIN, patch("/api/rooms/" + id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"" + name.toLowerCase() + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value(name.toLowerCase()))
				.andExpect(jsonPath("$.icon").value("door"));
		as(ADMIN, patch("/api/rooms/" + id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"" + other.toUpperCase() + "\"}"))
				.andExpect(status().isConflict());

		// Explicit null removes the icon, a missing field leaves it alone
		as(ADMIN, patch("/api/rooms/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"icon\": null}"))
				.andExpect(jsonPath("$.icon").doesNotExist());
		as(ADMIN, patch("/api/rooms/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"sort\": 42}"))
				.andExpect(jsonPath("$.sort").value(42))
				.andExpect(jsonPath("$.name").value(name.toLowerCase()));
	}

	@Test
	void orderNeedsEveryRoomExactlyOnce() throws Exception {
		createRoom(unique("Gästezimmer"), null);
		createRoom(unique("Abstellraum"), null);
		List<Long> ids = roomIds();
		List<Long> reversed = new ArrayList<>(ids.reversed());

		as(ADMIN, put("/api/rooms/order").contentType(MediaType.APPLICATION_JSON).content(order(reversed)))
				.andExpect(status().isNoContent());
		as(ADMIN, get("/api/rooms")).andExpect(jsonPath("$[*].id", contains(reversed.stream().map(Long::intValue).toArray())));

		as(ADMIN, put("/api/rooms/order").contentType(MediaType.APPLICATION_JSON).content(order(reversed.subList(1, reversed.size()))))
				.andExpect(status().isBadRequest());
		var duplicate = new ArrayList<>(reversed);
		duplicate.set(0, duplicate.get(1));
		as(ADMIN, put("/api/rooms/order").contentType(MediaType.APPLICATION_JSON).content(order(duplicate)))
				.andExpect(status().isBadRequest());
		var unknown = new ArrayList<>(reversed);
		unknown.add(999999L);
		as(ADMIN, put("/api/rooms/order").contentType(MediaType.APPLICATION_JSON).content(order(unknown)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void onlyEmptyRoomsCanBeDeleted() throws Exception {
		long empty = createRoom(unique("Dachboden"), null);
		as(ADMIN, delete("/api/rooms/" + empty)).andExpect(status().isNoContent());
		as(ADMIN, get("/api/rooms/" + empty)).andExpect(status().isNotFound());
		as(ADMIN, delete("/api/rooms/" + empty)).andExpect(status().isNotFound());

		long full = createRoom(unique("Garage"), null);
		long device = addDevice("Garagenlicht");
		moveDevice(device, String.valueOf(full)).andExpect(status().isOk());

		as(ADMIN, delete("/api/rooms/" + full))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.deviceCount").value(1));

		moveDevice(device, "null").andExpect(status().isOk());
		as(ADMIN, delete("/api/rooms/" + full)).andExpect(status().isNoContent());
	}

	@Test
	void devicesMoveBetweenRoomsAndCanBeFilteredByRoom() throws Exception {
		long room = createRoom(unique("Wohnzimmer"), "sofa");
		long lamp = addDevice("Stehlampe");
		long plug = addDevice("Steckdose");

		moveDevice(lamp, String.valueOf(room))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roomId").value(room));
		as(ADMIN, get("/api/rooms/" + room)).andExpect(jsonPath("$.deviceCount").value(1));
		as(ADMIN, get("/api/rooms")).andExpect(jsonPath("$[?(@.id == " + room + ")].deviceCount", contains(1)));

		as(ADMIN, get("/api/devices").param("roomId", String.valueOf(room)))
				.andExpect(jsonPath("$[*].id", contains((int) lamp)));
		as(ADMIN, get("/api/devices").param("roomId", "none"))
				.andExpect(jsonPath("$[*].id", hasItem((int) plug)))
				.andExpect(jsonPath("$[*].id", not(hasItem((int) lamp))));
		as(ADMIN, get("/api/devices").param("roomId", "abc")).andExpect(status().isBadRequest());
		as(ADMIN, get("/api/devices").param("roomId", "999999")).andExpect(status().isNotFound());

		// Renaming leaves the room alone, null takes the device out, unknown rooms are refused
		as(ADMIN, patch("/api/devices/" + lamp).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Leselampe\"}"))
				.andExpect(jsonPath("$.roomId").value(room));
		moveDevice(lamp, "999999").andExpect(status().isBadRequest());
		moveDevice(lamp, "null")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roomId").doesNotExist());
		as(ADMIN, get("/api/devices").param("roomId", "none")).andExpect(jsonPath("$[*].id", hasItem((int) lamp)));

		// Without any permission the device does not exist for a resident
		as(RESIDENT, patch("/api/devices/" + lamp).contentType(MediaType.APPLICATION_JSON)
				.content("{\"roomId\": " + room + "}"))
				.andExpect(status().isNotFound());
	}

	private long createRoom(String name, String icon) throws Exception {
		String body = icon == null ? "{\"name\": \"" + name + "\"}" : "{\"name\": \"" + name + "\", \"icon\": \"" + icon + "\"}";
		var result = as(ADMIN, post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn();
		long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith("/api/rooms/" + id);
		return id;
	}

	private List<Long> roomIds() throws Exception {
		String response = as(ADMIN, get("/api/rooms")).andReturn().getResponse().getContentAsString();
		List<Number> ids = JsonPath.read(response, "$[*].id");
		return ids.stream().map(Number::longValue).toList();
	}

	private long addDevice(String name) {
		var discovered = new DiscoveredDevice("sim", UUID.randomUUID().toString(), name, "light", null, null, null,
				Map.of(), Map.of());
		return devices.save(new Device(discovered, name, null)).getId();
	}

	private ResultActions moveDevice(long device, String roomIdJson) throws Exception {
		return as(ADMIN, patch("/api/devices/" + device).contentType(MediaType.APPLICATION_JSON)
				.content("{\"roomId\": " + roomIdJson + "}"));
	}

	private String aiToken() {
		long ki = users.findAll().stream().filter(u -> u.isMachine() && u.hasRole("ki")).findFirst().orElseThrow().getId();
		return tokens.create(ki, "rooms test").secret();
	}

	private static String order(List<Long> ids) {
		return "{\"roomIds\": " + ids + "}";
	}

	private static String unique(String name) {
		return name + " " + UUID.randomUUID().toString().substring(0, 8);
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

}
