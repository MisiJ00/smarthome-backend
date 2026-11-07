package dev.homeserver.smarthome.widget;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.device.DiscoveredDevice;

/**
 * Widgets per user (/api/me/widgets): create, change, order and delete only for what the user may view, never someone
 * else's, and the compact homescreen data that hides what the user may no longer see.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WidgetApiTest {

	private static final String ADMIN = TestJwts.token(UUID.randomUUID(), "admin@example.com", "Admin");

	@Autowired
	MockMvc mvc;

	@Autowired
	DeviceRepository devices;

	long room;
	Device lamp;
	Device otherLamp;
	Device plug;
	Device door;

	@BeforeEach
	void home() throws Exception {
		room = createRoom("Wohnzimmer " + suffix());
		lamp = addDevice("Stehlampe", "light", room, Map.of("state", "ON", "brightness", 200, "linkquality", 120));
		otherLamp = addDevice("Leselampe", "light", room, Map.of("state", "OFF", "brightness", 10, "linkquality", 90));
		plug = addDevice("Steckdose", "switch", null, Map.of("state", "ON", "power", 106.1));
		door = addDevice("Haustür", "lock", null, Map.of("state", "LOCK", "battery", 90));
	}

	@Test
	void createListChangeOrderAndDeleteMyWidgets() throws Exception {
		String erika = person("bewohner");
		as(erika, get("/api/me/widgets")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

		long first = id(create(erika, "switch", "device", lamp.getId()).andExpect(status().isCreated())
				.andExpect(jsonPath("$.kind").value("switch"))
				.andExpect(jsonPath("$.targetType").value("device"))
				.andExpect(jsonPath("$.position").value(0))
				.andReturn().getResponse().getContentAsString());
		long second = id(create(erika, "status", "device", plug.getId()).andReturn().getResponse().getContentAsString());
		long third = id(create(erika, "room", "room", room).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());
		as(erika, get("/api/me/widgets")).andExpect(jsonPath("$[*].id", contains((int) first, (int) second, (int) third)));

		// Change the kind (to one that fits the device) and the config; the target stays
		as(erika, patch("/api/me/widgets/" + first).contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\": \"dimmer\", \"config\": {\"label\": \"Licht\"}}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("dimmer"))
				.andExpect(jsonPath("$.config.label").value("Licht"))
				.andExpect(jsonPath("$.targetId").value(lamp.getId().intValue()));
		as(erika, patch("/api/me/widgets/" + first).contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"thermostat\"}"))
				.andExpect(status().isBadRequest());

		// New order has to name each widget exactly once
		as(erika, put("/api/me/widgets/order").contentType(MediaType.APPLICATION_JSON)
				.content("{\"widgetIds\": [" + third + ", " + first + ", " + second + "]}"))
				.andExpect(status().isNoContent());
		as(erika, get("/api/me/widgets")).andExpect(jsonPath("$[*].id", contains((int) third, (int) first, (int) second)));
		as(erika, put("/api/me/widgets/order").contentType(MediaType.APPLICATION_JSON)
				.content("{\"widgetIds\": [" + third + ", " + first + "]}"))
				.andExpect(status().isBadRequest());
		as(erika, put("/api/me/widgets/order").contentType(MediaType.APPLICATION_JSON)
				.content("{\"widgetIds\": [" + third + ", " + first + ", " + first + "]}"))
				.andExpect(status().isBadRequest());

		as(erika, delete("/api/me/widgets/" + first)).andExpect(status().isNoContent());
		as(erika, get("/api/me/widgets")).andExpect(jsonPath("$[*].id", contains((int) third, (int) second)));
		as(erika, delete("/api/me/widgets/" + first)).andExpect(status().isNotFound());
	}

	@Test
	void nobodyTouchesSomeoneElsesWidgets() throws Exception {
		String erika = person("bewohner");
		String max = person("bewohner");
		long mine = id(create(erika, "switch", "device", lamp.getId()).andReturn().getResponse().getContentAsString());

		as(max, get("/api/me/widgets")).andExpect(jsonPath("$", hasSize(0)));
		as(max, patch("/api/me/widgets/" + mine).contentType(MediaType.APPLICATION_JSON).content("{\"config\": {}}"))
				.andExpect(status().isNotFound());
		as(max, delete("/api/me/widgets/" + mine)).andExpect(status().isNotFound());
		as(max, get("/api/me/widgets/data")).andExpect(jsonPath("$.widgets", hasSize(0)));
		// Not even an admin reaches them through this API: it is always the caller's own list
		as(ADMIN, delete("/api/me/widgets/" + mine)).andExpect(status().isNotFound());
		as(erika, get("/api/me/widgets")).andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void onlyWhatTheUserMayViewAndWhatFitsCanBeAWidget() throws Exception {
		String nobody = person();
		// No right to see the lamp: the same answer as for a device that does not exist
		create(nobody, "switch", "device", lamp.getId()).andExpect(status().isBadRequest());
		create(nobody, "switch", "device", 987654321L).andExpect(status().isBadRequest());
		create(nobody, "room", "room", room).andExpect(status().isBadRequest());

		String erika = person("bewohner");
		// A kind has to fit the device type and the target type
		create(erika, "thermostat", "device", lamp.getId()).andExpect(status().isBadRequest());
		create(erika, "dimmer", "device", plug.getId()).andExpect(status().isBadRequest());
		create(erika, "lock", "device", lamp.getId()).andExpect(status().isBadRequest());
		create(erika, "room", "device", lamp.getId()).andExpect(status().isBadRequest());
		create(erika, "switch", "room", room).andExpect(status().isBadRequest());
		create(erika, "status", "scene", 1).andExpect(status().isBadRequest());
		// Unknown values and missing fields
		as(erika, post("/api/me/widgets").contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\": \"clock\", \"targetType\": \"device\", \"targetId\": " + lamp.getId() + "}"))
				.andExpect(status().isBadRequest());
		as(erika, post("/api/me/widgets").contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"switch\"}"))
				.andExpect(status().isBadRequest());
		// Fits: any device as status, a switch widget for a plug, a lock widget for the door
		create(erika, "status", "device", lamp.getId()).andExpect(status().isCreated());
		create(erika, "switch", "device", plug.getId()).andExpect(status().isCreated());
		create(erika, "lock", "device", door.getId()).andExpect(status().isCreated());
	}

	@Test
	void configIsKeptSmall() throws Exception {
		String erika = person("bewohner");
		String big = "x".repeat(WidgetService.MAX_CONFIG_BYTES + 1);
		as(erika, post("/api/me/widgets").contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\": \"switch\", \"targetType\": \"device\", \"targetId\": " + lamp.getId()
						+ ", \"config\": {\"note\": \"" + big + "\"}}"))
				.andExpect(status().isBadRequest());
		create(erika, "switch", "device", lamp.getId()).andExpect(status().isCreated())
				.andExpect(jsonPath("$.config").isMap());
	}

	@Test
	void homescreenDataIsCompactAndPerKind() throws Exception {
		String erika = person("bewohner");
		create(erika, "dimmer", "device", lamp.getId());
		create(erika, "status", "device", plug.getId());
		create(erika, "lock", "device", door.getId());
		create(erika, "room", "room", room);

		as(erika, get("/api/me/widgets/data")).andExpect(status().isOk())
				.andExpect(jsonPath("$.generatedAt").isNotEmpty())
				.andExpect(jsonPath("$.widgets", hasSize(4)))
				// a dimmer shows state and brightness only, not the radio quality
				.andExpect(jsonPath("$.widgets[0].name").value("Stehlampe"))
				.andExpect(jsonPath("$.widgets[0].available").value(true))
				.andExpect(jsonPath("$.widgets[0].state.state").value("ON"))
				.andExpect(jsonPath("$.widgets[0].state.brightness").value(200))
				.andExpect(jsonPath("$.widgets[0].state.linkquality").doesNotExist())
				.andExpect(jsonPath("$.widgets[0].actions[*].action", containsInAnyOrder("control")))
				// a status widget shows everything
				.andExpect(jsonPath("$.widgets[1].state.power").value(106.1))
				// the door: unlocking needs a confirmation, and everything else is left out
				.andExpect(jsonPath("$.widgets[2].state.state").value("LOCK"))
				.andExpect(jsonPath("$.widgets[2].actions[?(@.action == 'unlock')].requiresConfirmation", contains(true)))
				.andExpect(jsonPath("$.widgets[2].deviceCount").doesNotExist())
				// a room: how many devices, how many are on
				.andExpect(jsonPath("$.widgets[3].name").value(org.hamcrest.Matchers.startsWith("Wohnzimmer")))
				.andExpect(jsonPath("$.widgets[3].deviceCount").value(2))
				.andExpect(jsonPath("$.widgets[3].onCount").value(1))
				.andExpect(jsonPath("$.widgets[3].state").doesNotExist());
	}

	@Test
	void widgetsOfWhatIsNoLongerVisibleShowUpAsUnavailableWithoutDetails() throws Exception {
		String erika = person();
		long user = userId(erika);
		long grant = id(as(ADMIN, post("/api/grants").contentType(MediaType.APPLICATION_JSON)
				.content("{\"subjectType\": \"user\", \"subjectId\": " + user + ", \"scopeType\": \"device\", \"scopeId\": "
						+ lamp.getId() + ", \"action\": \"view\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		create(erika, "switch", "device", lamp.getId()).andExpect(status().isCreated());
		as(erika, get("/api/me/widgets/data")).andExpect(jsonPath("$.widgets[0].available").value(true))
				.andExpect(jsonPath("$.widgets[0].name").value("Stehlampe"));

		as(ADMIN, delete("/api/grants/" + grant)).andExpect(status().isNoContent());
		// The widget stays (the user may get the right back) but tells nothing
		as(erika, get("/api/me/widgets")).andExpect(jsonPath("$", hasSize(1)));
		as(erika, get("/api/me/widgets/data")).andExpect(jsonPath("$.widgets[0].available").value(false))
				.andExpect(jsonPath("$.widgets[0].name").doesNotExist())
				.andExpect(jsonPath("$.widgets[0].state").doesNotExist())
				.andExpect(jsonPath("$.widgets[0].actions").doesNotExist())
				.andExpect(jsonPath("$.widgets[0].targetId").value(lamp.getId().intValue()));
	}

	@Test
	void widgetsGoWhenTheirTargetIsDeleted() throws Exception {
		// Empty rooms are only visible to admins, so an admin has the widget; the trigger removes it with the room
		long empty = createRoom("Keller " + suffix());
		create(ADMIN, "room", "room", empty).andExpect(status().isCreated());
		long before = ((java.util.List<?>) JsonPath.read(
				as(ADMIN, get("/api/me/widgets")).andReturn().getResponse().getContentAsString(), "$")).size();
		as(ADMIN, delete("/api/rooms/" + empty)).andExpect(status().isNoContent());
		as(ADMIN, get("/api/me/widgets")).andExpect(jsonPath("$", hasSize((int) before - 1)));
	}

	@Test
	void theWidgetLimitIsEnforced() throws Exception {
		String erika = person("bewohner");
		for (int i = 0; i < WidgetService.MAX_WIDGETS; i++) {
			create(erika, "status", "device", lamp.getId()).andExpect(status().isCreated());
		}
		create(erika, "status", "device", lamp.getId()).andExpect(status().isBadRequest());
	}

	@Test
	void signedOutCallersGetNothing() throws Exception {
		mvc.perform(get("/api/me/widgets")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me/widgets/data")).andExpect(status().isUnauthorized());
	}

	// ---- helpers ----

	private ResultActions create(String bearer, String kind, String targetType, long targetId) throws Exception {
		return as(bearer, post("/api/me/widgets").contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\": \"" + kind + "\", \"targetType\": \"" + targetType + "\", \"targetId\": " + targetId + "}"));
	}

	private String person(String... roles) throws Exception {
		String token = TestJwts.token(UUID.randomUUID(), "p" + suffix() + "@example.com", "Person");
		long id = userId(token);
		as(ADMIN, put("/api/users/" + id + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content(roles.length == 0 ? "{\"roles\": []}" : "{\"roles\": [\"" + String.join("\", \"", roles) + "\"]}"))
				.andExpect(status().isOk());
		return token;
	}

	private long userId(String token) throws Exception {
		return id(as(token, get("/api/me")).andReturn().getResponse().getContentAsString());
	}

	private long createRoom(String name) throws Exception {
		return id(as(ADMIN, post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"" + name + "\"}")).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());
	}

	private Device addDevice(String name, String type, Long roomId, Map<String, Object> state) {
		var discovered = new DiscoveredDevice(RecordingDeviceAdapter.NAME, UUID.randomUUID().toString(), name, type, null,
				null, null, Map.of(), state);
		var device = new Device(discovered, name, roomId);
		device.setLockedActions(List.of());
		return devices.save(device);
	}

	private long id(String json) {
		return ((Number) JsonPath.read(json, "$.id")).longValue();
	}

	private static String suffix() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

}
