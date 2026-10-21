package dev.homeserver.smarthome.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import dev.homeserver.smarthome.RecordingDeviceAdapter;
import dev.homeserver.smarthome.TestJwts;
import dev.homeserver.smarthome.TestcontainersConfiguration;
import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.device.DiscoveredDevice;

/** Every error has the same form: problem detail with German title and detail and a stable code. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ErrorFormatTest {

	private static final String ADMIN = TestJwts.token(UUID.randomUUID(), "admin@example.com", "Admin");

	@Autowired
	MockMvc mvc;

	@Autowired
	DeviceRepository devices;

	@Test
	void ownErrorsHaveGermanTextsAndCodes() throws Exception {
		as(ADMIN, get("/api/devices/999999"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Nicht gefunden"))
				.andExpect(jsonPath("$.detail").value("Dieses Gerät gibt es nicht."))
				.andExpect(jsonPath("$.code").value("device.not_found"))
				.andExpect(jsonPath("$.status").value(404));

		String name = "Bad " + UUID.randomUUID().toString().substring(0, 8);
		as(ADMIN, post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + name + "\"}"))
				.andExpect(status().isCreated());
		as(ADMIN, post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + name.toUpperCase() + "\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Konflikt"))
				.andExpect(jsonPath("$.code").value("room.name_taken"))
				.andExpect(jsonPath("$.detail", containsString("gibt es schon")));
	}

	@Test
	void refusedCommandsSayWhyInGerman() throws Exception {
		var discovered = new DiscoveredDevice(RecordingDeviceAdapter.NAME, UUID.randomUUID().toString(), "Server",
				"switch", null, null, null, Map.of(), Map.of());
		var plug = new Device(discovered, "Server-Steckdose", null);
		plug.setLockedActions(List.of("switch_off"));
		long id = devices.save(plug).getId();

		as(ADMIN, post("/api/devices/" + id + "/commands").contentType(MediaType.APPLICATION_JSON).content("{\"state\": \"OFF\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("device.action_locked"))
				.andExpect(jsonPath("$.detail").value("„Ausschalten“ ist bei Server-Steckdose gesperrt."));
	}

	@Test
	void springsOwnErrorsGetTheSameForm() throws Exception {
		// Validation: field list with German messages
		as(ADMIN, post("/api/grants").contentType(MediaType.APPLICATION_JSON).content("{\"scopeType\": \"all\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("validation"))
				.andExpect(jsonPath("$.title").value("Ungültige Anfrage"))
				.andExpect(jsonPath("$.errors[*].field", hasItem("action")))
				.andExpect(jsonPath("$.errors[0].message").value("darf nicht fehlen"));
		// Broken JSON, wrong parameter type, unknown address
		as(ADMIN, post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content("{kaputt"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("request.unreadable"));
		as(ADMIN, get("/api/grants").param("subjectType", "niemand"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("request.parameter_invalid"));
		as(ADMIN, patch("/api/rooms/abc").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("request.parameter_invalid"));
	}

	@Test
	void notSignedInAndForbiddenHaveABodyToo() throws Exception {
		mvc.perform(get("/api/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
				.andExpect(jsonPath("$.code").value("auth.required"))
				.andExpect(jsonPath("$.title").value("Nicht angemeldet"));
		String resident = TestJwts.token(UUID.randomUUID(), "bewohner" + UUID.randomUUID() + "@example.com", "Erika");
		as(resident, get("/api/users"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("auth.forbidden"))
				.andExpect(jsonPath("$.detail").value("Dafür fehlen dir die Rechte."));
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

}
