package dev.homeserver.smarthome.token;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import dev.homeserver.smarthome.user.UserRepository;

/** Machine tokens: only admins hand them out, only machine users get them, revoking ends access at once. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ApiTokenTest {

	private static final String ADMIN = TestJwts.token(UUID.randomUUID(), "admin@example.com", "Admin");

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Test
	void aiSignsInWithTokenUntilItIsRevoked() throws Exception {
		String machineUsers = as(ADMIN, get("/api/tokens/machine-users"))
				.andExpect(jsonPath("$[0].name").value("KI"))
				.andReturn().getResponse().getContentAsString();
		int ki = JsonPath.read(machineUsers, "$[0].id");

		String response = as(ADMIN, post("/api/tokens").contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\": " + ki + ", \"name\": \"Ollama\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.secret", startsWith("shm_")))
				.andReturn().getResponse().getContentAsString();
		String secret = JsonPath.read(response, "$.secret");
		int tokenId = JsonPath.read(response, "$.token.id");

		as(secret, get("/api/me"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(ki))
				.andExpect(jsonPath("$.name").value("KI"))
				.andExpect(jsonPath("$.identityId", nullValue()))
				.andExpect(jsonPath("$.roles", contains("ki")));

		// The AI is not an admin
		as(secret, get("/api/devices")).andExpect(status().isOk());
		as(secret, get("/api/devices/discovered")).andExpect(status().isForbidden());
		as(secret, get("/api/tokens")).andExpect(status().isForbidden());

		// The list never contains the secret, but shows when the token was last used
		as(ADMIN, get("/api/tokens"))
				.andExpect(jsonPath("$[?(@.id == " + tokenId + ")].lastUsed", contains(notNullValue())))
				.andExpect(jsonPath("$[0].secret").doesNotExist());

		as(ADMIN, delete("/api/tokens/" + tokenId)).andExpect(status().isNoContent());
		as(secret, get("/api/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void unknownTokensAreRejected() throws Exception {
		as("shm_doesnotexist", get("/api/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void onlyAdminsCreateTokensAndOnlyForMachines() throws Exception {
		String resident = TestJwts.token(UUID.randomUUID(), "bewohner@example.com", "Erika");
		as(resident, get("/api/me")).andExpect(status().isOk());
		long human = users.findAll().stream().filter(u -> "Erika".equals(u.getName())).findFirst().orElseThrow().getId();

		as(resident, post("/api/tokens").contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\": " + human + ", \"name\": \"mein Skript\"}"))
				.andExpect(status().isForbidden());
		as(ADMIN, post("/api/tokens").contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\": " + human + ", \"name\": \"mein Skript\"}"))
				.andExpect(status().isUnprocessableContent());
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

}
