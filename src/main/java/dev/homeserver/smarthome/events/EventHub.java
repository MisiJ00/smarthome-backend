package dev.homeserver.smarthome.events;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import dev.homeserver.smarthome.grant.PermissionService;
import dev.homeserver.smarthome.grant.PermissionService.Access;
import dev.homeserver.smarthome.token.ApiTokenService;
import dev.homeserver.smarthome.user.CurrentUser;
import dev.homeserver.smarthome.user.Role;
import dev.homeserver.smarthome.user.UserRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The open event connections ({@code /api/events}). Every message is {@code {"type", "ts", "data"}}. The hub keeps
 * each connection's user and permissions up to date, pings every heartbeat and closes connections whose user was
 * deactivated or deleted (4403), whose machine token was revoked (4401), or that are older than the maximum age
 * (4000, the client reconnects with a new ticket).
 */
@Component
public class EventHub extends TextWebSocketHandler {

	public static final CloseStatus RECONNECT = new CloseStatus(4000, "Connection too old, reconnect with a new ticket");
	public static final CloseStatus TOKEN_REVOKED = new CloseStatus(4401, "Token revoked");
	public static final CloseStatus ACCESS_ENDED = new CloseStatus(4403, "User deactivated or deleted");

	static final String USER_ID = "userId";
	static final String API_TOKEN_ID = "apiTokenId";

	private static final Logger log = LoggerFactory.getLogger(EventHub.class);

	/** One open connection and what its user may currently see. */
	public static final class Connection {

		private final WebSocketSession session;
		private final long userId;
		private final Long apiTokenId;
		private final Instant openedAt = Instant.now();
		private volatile CurrentUser user;
		private volatile Access access;

		Connection(WebSocketSession session, long userId, Long apiTokenId) {
			this.session = session;
			this.userId = userId;
			this.apiTokenId = apiTokenId;
		}

		public CurrentUser user() {
			return user;
		}

		public Access access() {
			return access;
		}

		public boolean isAdmin() {
			return user.roles().contains(Role.ADMIN);
		}

	}

	record Envelope(String type, Instant ts, Object data) {
	}

	private final Map<String, Connection> connections = new ConcurrentHashMap<>();
	private final UserRepository users;
	private final PermissionService permissions;
	private final ApiTokenService tokens;
	private final TicketService tickets;
	private final EventProperties properties;
	private final JsonMapper json;

	public EventHub(UserRepository users, PermissionService permissions, ApiTokenService tokens, TicketService tickets,
			EventProperties properties, JsonMapper json) {
		this.users = users;
		this.permissions = permissions;
		this.tokens = tokens;
		this.tickets = tickets;
		this.properties = properties;
		this.json = json;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		var safe = new ConcurrentWebSocketSessionDecorator(session, 10_000, 512 * 1024);
		var connection = new Connection(safe, (Long) session.getAttributes().get(USER_ID),
				(Long) session.getAttributes().get(API_TOKEN_ID));
		if (!refresh(connection)) {
			return;
		}
		connections.put(session.getId(), connection);
		send(connection, "hello", Map.of(
				"userId", connection.userId,
				"name", connection.user.name(),
				"reconnectBefore", connection.openedAt.plus(properties.maxConnectionAge()),
				"hint", "Events only carry changes: load the current state via the REST API now and after every reconnect."));
	}

	/** Clients may send {@code {"type": "ping"}} and get a {@code pong}. */
	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		var connection = connections.get(session.getId());
		if (connection == null) {
			return;
		}
		String type;
		try {
			type = json.readTree(message.getPayload()).path("type").asString("");
		}
		catch (RuntimeException e) {
			type = "";
		}
		if (type.equals("ping")) {
			send(connection, "pong", Map.of());
		}
		else {
			send(connection, "error", Map.of("message", "Unknown message; clients only send {\"type\": \"ping\"}"));
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		connections.remove(session.getId());
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		connections.remove(session.getId());
	}

	public List<Connection> connections() {
		return List.copyOf(connections.values());
	}

	public void send(Connection connection, String type, Object data) {
		try {
			String text = json.writeValueAsString(new Envelope(type, Instant.now(), data));
			connection.session.sendMessage(new TextMessage(text));
		}
		catch (IOException | RuntimeException e) {
			log.debug("Dropping event connection {}: {}", connection.session.getId(), e.toString());
			close(connection, CloseStatus.SERVER_ERROR);
		}
	}

	/** Reloads user and permissions; closes the connection if the user is gone or deactivated. */
	public boolean refresh(Connection connection) {
		var user = users.findById(connection.userId).filter(u -> u.isActive()).orElse(null);
		if (user == null) {
			close(connection, ACCESS_ENDED);
			return false;
		}
		var current = CurrentUser.of(user, null);
		connection.user = current;
		connection.access = permissions.accessOf(current);
		return true;
	}

	public void closeUser(long userId) {
		connections().stream().filter(c -> c.userId == userId).forEach(c -> close(c, ACCESS_ENDED));
	}

	public void closeToken(long tokenId) {
		connections().stream().filter(c -> c.apiTokenId != null && c.apiTokenId == tokenId)
				.forEach(c -> close(c, TOKEN_REVOKED));
	}

	/** Pings every connection and checks that it may stay open. */
	@Scheduled(fixedDelayString = "${smarthome.events.heartbeat:30s}")
	public void heartbeat() {
		tickets.removeExpired();
		Instant oldest = Instant.now().minus(properties.maxConnectionAge());
		for (var connection : connections()) {
			if (connection.openedAt.isBefore(oldest)) {
				close(connection, RECONNECT);
			}
			else if (connection.apiTokenId != null && !tokens.exists(connection.apiTokenId)) {
				close(connection, TOKEN_REVOKED);
			}
			else if (refresh(connection)) {
				try {
					connection.session.sendMessage(new PingMessage());
				}
				catch (IOException | RuntimeException e) {
					close(connection, CloseStatus.SERVER_ERROR);
				}
			}
		}
	}

	private void close(Connection connection, CloseStatus status) {
		connections.remove(connection.session.getId());
		try {
			connection.session.close(status);
		}
		catch (IOException e) {
			// Already gone
		}
	}

}
