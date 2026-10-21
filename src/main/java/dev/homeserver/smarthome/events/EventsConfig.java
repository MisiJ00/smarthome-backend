package dev.homeserver.smarthome.events;

import java.util.Map;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@code /api/events}: plain WebSocket with JSON messages. The handshake needs a ticket from
 * {@code POST /api/events/ticket} ({@code ?ticket=}); without a valid one it is refused with 401. Any origin is allowed,
 * because no cookies are involved: the ticket is the only credential.
 */
@Configuration
@EnableWebSocket
class EventsConfig implements WebSocketConfigurer {

	private final EventHub hub;
	private final TicketService tickets;

	EventsConfig(EventHub hub, TicketService tickets) {
		this.hub = hub;
		this.tickets = tickets;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(hub, "/api/events")
				.addInterceptors(new TicketInterceptor(tickets))
				.setAllowedOriginPatterns("*");
	}

	static final class TicketInterceptor implements HandshakeInterceptor {

		private final TicketService tickets;

		TicketInterceptor(TicketService tickets) {
			this.tickets = tickets;
		}

		@Override
		public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
				Map<String, Object> attributes) {
			String value = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
			var ticket = tickets.redeem(value);
			if (ticket.isEmpty()) {
				response.setStatusCode(HttpStatus.UNAUTHORIZED);
				return false;
			}
			attributes.put(EventHub.USER_ID, ticket.get().userId());
			if (ticket.get().apiTokenId() != null) {
				attributes.put(EventHub.API_TOKEN_ID, ticket.get().apiTokenId());
			}
			return true;
		}

		@Override
		public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
				Exception exception) {
		}

	}

}
