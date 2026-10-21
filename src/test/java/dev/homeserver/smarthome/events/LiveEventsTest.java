package dev.homeserver.smarthome.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
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

import com.jayway.jsonpath.JsonPath;

import dev.homeserver.smarthome.RecordingDeviceAdapter;
import dev.homeserver.smarthome.TestJwts;
import dev.homeserver.smarthome.TestcontainersConfiguration;
import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.device.DeviceStateChanged;
import dev.homeserver.smarthome.device.DiscoveredDevice;
import dev.homeserver.smarthome.user.Role;
import dev.homeserver.smarthome.user.UserRepository;

/** Live updates over /api/events: who gets which event, and who gets in at all. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class LiveEventsTest {

	private static final String ADMIN = TestJwts.token(UUID.randomUUID(), "admin@example.com", "Admin");
	private static final Duration WAIT = Duration.ofSeconds(10);

	@LocalServerPort
	int port;

	@Autowired
	MockMvc mvc;

	@Autowired
	DeviceRepository devices;

	@Autowired
	UserRepository users;

	@Autowired
	ApplicationEventPublisher events;

	@Autowired
	EventHub hub;

	List<Client> clients = new ArrayList<>();
	long livingRoom;
	Device lamp;
	Device door;

	@BeforeEach
	void home() throws Exception {
		livingRoom = createRoom("Wohnzimmer " + suffix());
		lamp = addDevice("Stehlampe", "light", livingRoom);
		door = addDevice("Haustür", "lock", null);
	}

	@AfterEach
	void disconnect() {
		clients.forEach(Client::close);
	}

	@Test
	void connectingNeedsAFreshOneTimeTicket() throws Exception {
		mvc.perform(post("/api/events/ticket")).andExpect(status().isUnauthorized());
		as("shm_doesnotexist", post("/api/events/ticket")).andExpect(status().isUnauthorized());

		assertThatThrownBy(() -> open(null)).hasMessageContaining("401");
		assertThatThrownBy(() -> open("made-up")).hasMessageContaining("401");

		String ticket = ticket(ADMIN);
		var client = open(ticket);
		var hello = client.await("hello", m -> true);
		assertThat(JsonPath.<String>read(hello, "$.data.name")).isEqualTo("Admin");
		assertThat(JsonPath.<String>read(hello, "$.ts")).isNotBlank();
		// The same ticket does not work twice
		assertThatThrownBy(() -> open(ticket)).hasMessageContaining("401");

		client.send("{\"type\": \"ping\"}");
		client.await("pong", m -> true);
	}

	@Test
	void deviceStateOnlyReachesThoseWhoMayViewTheDevice() throws Exception {
		var admin = connect(ADMIN);
		var resident = connect(person("bewohner"));
		var nobody = connect(person());

		events.publishEvent(new DeviceStateChanged(RecordingDeviceAdapter.NAME, lamp.getExternalId(), Map.of("state", "ON")));

		Predicate<String> isLamp = m -> JsonPath.<Integer>read(m, "$.data.deviceId") == lamp.getId().intValue();
		var received = resident.await("device.state", isLamp);
		assertThat(JsonPath.<String>read(received, "$.data.state.state")).isEqualTo("ON");
		assertThat(JsonPath.<String>read(received, "$.data.lastSeen")).isNotBlank();
		admin.await("device.state", isLamp);
		nobody.assertNone("device.state");
	}

	@Test
	void permissionChangesTakeEffectWhileConnected() throws Exception {
		String token = person();
		long user = userId(token);
		var client = connect(token);

		// A grant on the living room: the user is told to reload and then sees the lamp's state
		as(ADMIN, post("/api/grants").contentType(MediaType.APPLICATION_JSON)
				.content("{\"subjectType\": \"user\", \"subjectId\": " + user + ", \"scopeType\": \"room\", \"scopeId\": "
						+ livingRoom + ", \"action\": \"view\"}"))
				.andExpect(status().isCreated());
		client.await("permissions.changed", m -> true);
		events.publishEvent(new DeviceStateChanged(RecordingDeviceAdapter.NAME, lamp.getExternalId(), Map.of("state", "OFF")));
		client.await("device.state", m -> JsonPath.<Integer>read(m, "$.data.deviceId") == lamp.getId().intValue());

		// The lamp moves out of the room: for this user it is gone
		as(ADMIN, patch("/api/devices/" + lamp.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"roomId\": null}"))
				.andExpect(status().isOk());
		client.await("device.removed", m -> JsonPath.<Integer>read(m, "$.data.deviceId") == lamp.getId().intValue());
	}

	@Test
	void devicesAndRoomsChangesFollowTheSameRules() throws Exception {
		var admin = connect(ADMIN);
		var guest = connect(person("gast"));

		as(ADMIN, patch("/api/devices/" + lamp.getId()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"Leselampe\"}"))
				.andExpect(status().isOk());
		var updated = guest.await("device.updated", m -> JsonPath.<String>read(m, "$.data.name").equals("Leselampe"));
		// Each client gets its own permissions with the device
		assertThat(JsonPath.<List<String>>read(updated, "$.data.permissions[*].action")).containsExactly("view");
		admin.await("device.updated", m -> JsonPath.<String>read(m, "$.data.name").equals("Leselampe"));

		// A new room is empty, so only admins hear about it
		long room = createRoom("Keller " + suffix());
		admin.await("room.created", m -> JsonPath.<Integer>read(m, "$.data.id") == (int) room);
		as(ADMIN, delete("/api/rooms/" + room)).andExpect(status().isNoContent());
		admin.await("room.deleted", m -> JsonPath.<Integer>read(m, "$.data.roomId") == (int) room);
		guest.assertNone("room.created");
		guest.assertNone("room.deleted");

		// The guest sees the living room (the lamp is in it) and gets its changes
		as(ADMIN, patch("/api/rooms/" + livingRoom).contentType(MediaType.APPLICATION_JSON).content("{\"icon\": \"sofa\"}"))
				.andExpect(status().isOk());
		guest.await("room.updated", m -> JsonPath.<Integer>read(m, "$.data.id") == (int) livingRoom);
	}

	@Test
	void confirmationsReachTheRequesterAndThoseWhoMayDecide() throws Exception {
		String erikaToken = person("bewohner");
		var erika = connect(erikaToken);
		String maxToken = person("bewohner");
		var max = connect(maxToken);
		var guest = connect(person("gast"));

		String response = as(erikaToken, post("/api/devices/" + door.getId() + "/commands")
				.contentType(MediaType.APPLICATION_JSON).content("{\"state\": \"UNLOCK\"}"))
				.andReturn().getResponse().getContentAsString();
		int id = JsonPath.read(response, "$.confirmationId");
		Predicate<String> isIt = m -> JsonPath.<Integer>read(m, "$.data.id") == id;

		var own = erika.await("confirmation.created", isIt);
		assertThat(JsonPath.<Boolean>read(own, "$.data.mine")).isTrue();
		var forMax = max.await("confirmation.created", isIt);
		assertThat(JsonPath.<Boolean>read(forMax, "$.data.canDecide")).isTrue();
		guest.assertNone("confirmation.created");

		as(maxToken, post("/api/confirmations/" + id + "/approve")).andExpect(status().isOk());
		erika.await("confirmation.approved", isIt);
		max.await("confirmation.approved", isIt);
		guest.assertNone("confirmation.approved");
	}

	@Test
	void connectionsEndWhenAccessEnds() throws Exception {
		// A real account (in Kratos), so it can be deactivated there too
		String username = "live" + suffix();
		String account = as(ADMIN, post("/api/users").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\": \"" + username + "@example.com\", \"username\": \"" + username
						+ "\", \"name\": \"Live\", \"roles\": [\"bewohner\"]}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long user = ((Number) JsonPath.read(account, "$.user.id")).longValue();
		UUID identity = users.findById(user).orElseThrow().getIdentityId();
		var person = connect(TestJwts.sign(TestJwts.claims(identity,
				Map.of("email", username + "@example.com", "name", "Live", "preferred_username", username)).build()));

		String created = as(ADMIN, post("/api/tokens").contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\": " + kiUserId() + ", \"name\": \"events test\"}"))
				.andReturn().getResponse().getContentAsString();
		var ai = connect(JsonPath.read(created, "$.secret"));
		ai.await("hello", m -> true);

		// The AI sees devices like everyone else with view
		events.publishEvent(new DeviceStateChanged(RecordingDeviceAdapter.NAME, door.getExternalId(), Map.of("battery", 50)));
		ai.await("device.state", m -> JsonPath.<Integer>read(m, "$.data.deviceId") == door.getId().intValue());

		as(ADMIN, delete("/api/tokens/" + JsonPath.read(created, "$.token.id"))).andExpect(status().isNoContent());
		assertThat(ai.closed.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(4401);

		// A heartbeat leaves active users connected; deactivating closes at once
		hub.heartbeat();
		assertThat(person.closed.isDone()).isFalse();
		as(ADMIN, patch("/api/users/" + user).contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
				.andExpect(status().isOk());
		assertThat(person.closed.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(4403);
	}

	/** A connected client that records everything it receives. */
	static final class Client extends TextWebSocketHandler {

		final List<String> messages = new CopyOnWriteArrayList<>();
		final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
		WebSocketSession session;

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			messages.add(message.getPayload());
		}

		@Override
		public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
			closed.complete(status);
		}

		String await(String type, Predicate<String> matches) throws InterruptedException {
			long until = System.nanoTime() + WAIT.toNanos();
			while (System.nanoTime() < until) {
				for (String m : messages) {
					if (JsonPath.<String>read(m, "$.type").equals(type) && matches.test(m)) {
						return m;
					}
				}
				Thread.sleep(50);
			}
			throw new AssertionError("No " + type + " event, got " + messages);
		}

		/** Nothing of this type arrives, waiting a moment so late events would show up. */
		void assertNone(String type) throws InterruptedException {
			Thread.sleep(500);
			assertThat(messages).noneMatch(m -> JsonPath.<String>read(m, "$.type").equals(type));
		}

		void send(String text) throws Exception {
			session.sendMessage(new TextMessage(text));
		}

		void close() {
			try {
				if (session != null && session.isOpen()) {
					session.close();
				}
			}
			catch (Exception e) {
				// ignore
			}
		}

	}

	private Client connect(String bearer) throws Exception {
		var client = open(ticket(bearer));
		client.await("hello", m -> true);
		return client;
	}

	private Client open(String ticket) throws Exception {
		var client = new Client();
		String url = "ws://localhost:" + port + "/api/events" + (ticket == null ? "" : "?ticket=" + ticket);
		client.session = new StandardWebSocketClient().execute(client, url).get(10, TimeUnit.SECONDS);
		clients.add(client);
		return client;
	}

	private String ticket(String bearer) throws Exception {
		String response = as(bearer, post("/api/events/ticket")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.ticket");
	}

	/** A signed-in person with exactly these roles. */
	private String person(String... roles) throws Exception {
		String token = TestJwts.token(UUID.randomUUID(), "p" + suffix() + "@example.com", "Person");
		long id = userId(token);
		as(ADMIN, put("/api/users/" + id + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content(roles.length == 0 ? "{\"roles\": []}" : "{\"roles\": [\"" + String.join("\", \"", roles) + "\"]}"))
				.andExpect(status().isOk());
		return token;
	}

	private long userId(String token) throws Exception {
		String me = as(token, get("/api/me")).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(me, "$.id")).longValue();
	}

	private long kiUserId() {
		return users.findAll().stream().filter(u -> u.isMachine() && u.hasRole(Role.KI)).findFirst().orElseThrow().getId();
	}

	private long createRoom(String name) throws Exception {
		String response = as(ADMIN, post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"" + name + "\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(response, "$.id")).longValue();
	}

	private Device addDevice(String name, String type, Long roomId) {
		var discovered = new DiscoveredDevice(RecordingDeviceAdapter.NAME, UUID.randomUUID().toString(), name, type, null,
				null, null, Map.of(), Map.of());
		return devices.save(new Device(discovered, name, roomId));
	}

	private static String suffix() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

}
