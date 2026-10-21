package dev.homeserver.smarthome.z2m;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.GenericContainer;

import com.jayway.jsonpath.JsonPath;

import dev.homeserver.simulator.Simulator;
import dev.homeserver.smarthome.TestJwts;
import dev.homeserver.smarthome.TestcontainersConfiguration;

/**
 * Pairing mode against the Zigbee2MQTT simulator: admins open it, a new lamp joins, shows up as discovered (also as
 * live event for admins only) and can be added. Everything ends up in the audit log.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class PairingTest {

	private static final String ADMIN = TestJwts.token(UUID.randomUUID(), "admin@example.com", "Admin");

	@LocalServerPort
	int port;

	@Autowired
	MockMvc mvc;

	@Autowired
	GenericContainer<?> mosquittoContainer;

	Simulator simulator;
	List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

	@BeforeEach
	void startSimulator() throws Exception {
		simulator = Simulator.withStandardDevices(TestcontainersConfiguration.mqttUrl(mosquittoContainer), "simulator",
				TestcontainersConfiguration.MQTT_SIMULATOR_PASSWORD, "zigbee2mqtt", 1);
		simulator.start();
		// The simulator reports bridge/info on connect
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> as(ADMIN, get("/api/adapters/z2m/permit-join"))
				.andExpect(jsonPath("$.known").value(true))
				.andExpect(jsonPath("$.active").value(false)));
	}

	@AfterEach
	void stopSimulator() throws Exception {
		for (var session : sessions) {
			if (session.isOpen()) {
				session.close();
			}
		}
		if (simulator != null) {
			simulator.stop();
		}
	}

	@Test
	void adminPairsANewLamp() throws Exception {
		var admin = connect(ADMIN);
		var resident = connect(resident());

		as(ADMIN, post("/api/adapters/z2m/permit-join").contentType(MediaType.APPLICATION_JSON)
				.content("{\"seconds\": 60}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.endsAt").exists());
		var started = admin.receive("pairing.started");
		Instant endsAt = Instant.parse(JsonPath.read(started, "$.data.endsAt"));
		assertThat(endsAt).isBetween(Instant.now().plusSeconds(50), Instant.now().plusSeconds(70));

		// After a second the simulator lets "Neue Lampe" join
		var discovered = admin.receive("device.discovered");
		String externalId = JsonPath.read(discovered, "$.data.externalId");
		assertThat(JsonPath.<String>read(discovered, "$.data.suggestedName")).startsWith("Neue Lampe");
		assertThat(JsonPath.<String>read(discovered, "$.data.type")).isEqualTo("light");
		as(ADMIN, get("/api/devices/discovered")).andExpect(jsonPath("$[*].externalId", hasItem(externalId)));
		as(ADMIN, post("/api/devices").contentType(MediaType.APPLICATION_JSON)
				.content("{\"adapter\": \"z2m\", \"externalId\": \"" + externalId + "\", \"name\": \"Flurlampe\"}"))
				.andExpect(status().isCreated());

		as(ADMIN, delete("/api/adapters/z2m/permit-join"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));
		admin.receive("pairing.stopped");
		as(ADMIN, get("/api/adapters/z2m/permit-join")).andExpect(jsonPath("$.active").value(false));

		// Residents hear nothing about pairing
		Thread.sleep(300);
		assertThat(resident.types()).doesNotContain("pairing.started", "pairing.stopped", "device.discovered");

		as(ADMIN, get("/api/audit").param("action", "permit_join"))
				.andExpect(jsonPath("$.items[0].result").value("stopped"))
				.andExpect(jsonPath("$.items[1].result").value("started"))
				.andExpect(jsonPath("$.items[1].params.seconds").value(60));
	}

	@Test
	void onlyAdminsWithinZigbee2mqttLimits() throws Exception {
		String resident = resident();
		as(resident, post("/api/adapters/z2m/permit-join")).andExpect(status().isForbidden());
		as(resident, get("/api/adapters/z2m/permit-join")).andExpect(status().isForbidden());

		as(ADMIN, post("/api/adapters/z2m/permit-join").contentType(MediaType.APPLICATION_JSON).content("{\"seconds\": 0}"))
				.andExpect(status().isBadRequest());
		as(ADMIN, post("/api/adapters/z2m/permit-join").contentType(MediaType.APPLICATION_JSON).content("{\"seconds\": 255}"))
				.andExpect(status().isBadRequest());

		// Without a body: the maximum of 254 seconds
		as(ADMIN, post("/api/adapters/z2m/permit-join"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true));
		as(ADMIN, get("/api/audit").param("action", "permit_join"))
				.andExpect(jsonPath("$.items[0].params.seconds").value(254));
		as(ADMIN, delete("/api/adapters/z2m/permit-join")).andExpect(status().isOk());
	}

	@Test
	void reportsWhenZigbee2mqttDoesNotAnswer() throws Exception {
		simulator.stop();
		simulator = null;
		as(ADMIN, post("/api/adapters/z2m/permit-join").contentType(MediaType.APPLICATION_JSON)
				.content("{\"seconds\": 30}"))
				.andExpect(status().isGatewayTimeout());
	}

	// --- helpers

	static final class Client extends TextWebSocketHandler {

		final List<String> messages = new CopyOnWriteArrayList<>();
		final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			messages.add(message.getPayload());
		}

		@Override
		public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
			closed.complete(status);
		}

		String receive(String type) {
			return await().atMost(Duration.ofSeconds(10)).until(
					() -> messages.stream().filter(m -> JsonPath.<String>read(m, "$.type").equals(type)).findFirst()
							.orElse(null),
					m -> m != null);
		}

		List<String> types() {
			return messages.stream().map(m -> JsonPath.<String>read(m, "$.type")).toList();
		}

	}

	private Client connect(String bearer) throws Exception {
		String response = as(bearer, post("/api/events/ticket")).andReturn().getResponse().getContentAsString();
		var client = new Client();
		sessions.add(new StandardWebSocketClient()
				.execute(client, "ws://localhost:" + port + "/api/events?ticket=" + JsonPath.read(response, "$.ticket"))
				.get(10, TimeUnit.SECONDS));
		client.receive("hello");
		return client;
	}

	private String resident() throws Exception {
		String token = TestJwts.token(UUID.randomUUID(), "p" + UUID.randomUUID().toString().substring(0, 8)
				+ "@example.com", "Bewohner");
		String me = as(token, get("/api/me")).andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(me, "$.id")).longValue();
		as(ADMIN, put("/api/users/" + id + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"roles\": [\"bewohner\"]}"))
				.andExpect(status().isOk());
		return token;
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

}
