package dev.homeserver.smarthome.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

import dev.homeserver.smarthome.RecordingDeviceAdapter;
import dev.homeserver.smarthome.TestJwts;
import dev.homeserver.smarthome.TestcontainersConfiguration;
import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.device.DiscoveredDevice;
import dev.homeserver.smarthome.token.ApiTokenService;
import dev.homeserver.smarthome.user.Role;
import dev.homeserver.smarthome.user.UserRepository;

/** Approval requests go out as ntfy pushes to the people who may decide them; a fake ntfy records what arrives. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalNotifierTest {

	private static final String ADMIN = TestJwts.token(UUID.randomUUID(), "admin@example.com", "Admin");
	private static final String TOKEN = "tk_testtokentesttokentesttoken1";

	private static final List<Received> received = new CopyOnWriteArrayList<>();
	private static final HttpServer ntfy = fakeNtfy();

	record Received(String authorization, String body) {
	}

	@DynamicPropertySource
	static void ntfyProperties(DynamicPropertyRegistry registry) {
		registry.add("smarthome.ntfy.url", () -> "http://localhost:" + ntfy.getAddress().getPort() + "/");
		registry.add("smarthome.ntfy.public-url", () -> "http://homeserver:8090");
		registry.add("smarthome.ntfy.token", () -> TOKEN);
		registry.add("smarthome.ntfy.topic-secret", () -> "test-secret");
	}

	@AfterAll
	static void stop() {
		ntfy.stop(0);
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	DeviceRepository devices;

	@Autowired
	UserRepository users;

	@Autowired
	ApiTokenService tokens;

	@Autowired
	NtfyTopics topics;

	@Test
	void anUnlockRequestReachesTheOtherResidentsButNotTheRequester() throws Exception {
		Device door = addDoor();
		String erika = personWithRoles("Erika", "bewohner");
		String max = personWithRoles("Max", "bewohner");
		String guest = personWithRoles("Gast", "gast");

		as(erika, post("/api/devices/" + door.getId() + "/commands").header("X-Smarthome-Client", "app")
				.contentType(MediaType.APPLICATION_JSON).content("{\"state\": \"UNLOCK\"}"))
				.andExpect(status().isAccepted());

		String maxTopic = topicOf(max);
		var push = awaitPush(maxTopic);
		assertThat(push.authorization()).isEqualTo("Bearer " + TOKEN);
		assertThat((String) JsonPath.read(push.body(), "$.title")).isEqualTo("Freigabe angefragt");
		assertThat((String) JsonPath.read(push.body(), "$.message")).isEqualTo("Erika möchte " + door.getName() + " öffnen");
		assertThat((String) JsonPath.read(push.body(), "$.click")).isEqualTo("smarthome://");
		assertThat((List<String>) JsonPath.read(push.body(), "$.tags")).containsExactly("door");

		Thread.sleep(300);
		assertThat(topicsReceived()).doesNotContain(topicOf(erika), topicOf(guest));
	}

	@Test
	void everyoneSeesOnlyTheirOwnTopic() throws Exception {
		String erika = personWithRoles("Erika", "bewohner");
		String max = personWithRoles("Max", "bewohner");
		as(erika, get("/api/me/notifications"))
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.server").value("http://homeserver:8090"))
				.andExpect(jsonPath("$.topic").value(topics.of(idOf(erika))));
		assertThat(topicOf(erika)).startsWith("sh_").hasSize(35).isNotEqualTo(topicOf(max));
		as(aiToken(), get("/api/me/notifications")).andExpect(jsonPath("$.enabled").value(false));
	}

	private Received awaitPush(String topic) throws InterruptedException {
		Instant until = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(until)) {
			for (Received r : received) {
				if (topic.equals(JsonPath.read(r.body(), "$.topic"))) {
					return r;
				}
			}
			Thread.sleep(50);
		}
		throw new AssertionError("No push for " + topic + ", got " + topicsReceived());
	}

	private static List<String> topicsReceived() {
		var result = new ArrayList<String>();
		received.forEach(r -> result.add(JsonPath.read(r.body(), "$.topic")));
		return result;
	}

	private String topicOf(String bearer) throws Exception {
		return topics.of(idOf(bearer));
	}

	private long idOf(String bearer) throws Exception {
		String me = as(bearer, get("/api/me")).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(me, "$.id")).longValue();
	}

	private String personWithRoles(String name, String... roles) throws Exception {
		String token = TestJwts.token(UUID.randomUUID(), "p" + suffix() + "@example.com", name);
		as(ADMIN, put("/api/users/" + idOf(token) + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"roles\": [\"" + String.join("\", \"", roles) + "\"]}"))
				.andExpect(status().isOk());
		return token;
	}

	private String aiToken() {
		long ki = users.findAll().stream().filter(u -> u.isMachine() && u.hasRole(Role.KI)).findFirst().orElseThrow().getId();
		return tokens.create(ki, "notification test").secret();
	}

	private Device addDoor() {
		String name = "Haustür " + suffix();
		var discovered = new DiscoveredDevice(RecordingDeviceAdapter.NAME, UUID.randomUUID().toString(), name, "lock",
				null, null, null, Map.of(), Map.of());
		return devices.save(new Device(discovered, name, null));
	}

	private static String suffix() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

	private static HttpServer fakeNtfy() {
		try {
			var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/", exchange -> {
				String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				received.add(new Received(exchange.getRequestHeaders().getFirst("Authorization"), body));
				exchange.sendResponseHeaders(200, -1);
				exchange.close();
			});
			server.start();
			return server;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

}
