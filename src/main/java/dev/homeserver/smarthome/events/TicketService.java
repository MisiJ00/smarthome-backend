package dev.homeserver.smarthome.events;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * One-time tickets for opening the event connection. Browsers cannot send an {@code Authorization} header with a
 * WebSocket, so clients first fetch a ticket with their normal token and pass it as {@code ?ticket=}. A ticket is valid
 * for a few seconds and only once, so it is worthless in a log afterwards. Kept in memory: there is one backend.
 */
@Service
public class TicketService {

	/** Who a ticket was issued to; {@code apiTokenId} is set for machine users. */
	public record Ticket(long userId, Long apiTokenId, Instant expiresAt) {
	}

	private final SecureRandom random = new SecureRandom();
	private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
	private final EventProperties properties;

	public TicketService(EventProperties properties) {
		this.properties = properties;
	}

	/** What the client gets: the value for {@code ?ticket=} and until when it can be used. */
	public record IssuedTicket(String ticket, Instant expiresAt) {
	}

	public IssuedTicket issue(long userId, Long apiTokenId) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		var ticket = new Ticket(userId, apiTokenId, Instant.now().plus(properties.ticketLifetime()));
		tickets.put(value, ticket);
		return new IssuedTicket(value, ticket.expiresAt());
	}

	/** The ticket if it exists and has not expired; it cannot be used again either way. */
	public Optional<Ticket> redeem(String value) {
		if (value == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(tickets.remove(value)).filter(t -> t.expiresAt().isAfter(Instant.now()));
	}

	void removeExpired() {
		Instant now = Instant.now();
		tickets.values().removeIf(t -> !t.expiresAt().isAfter(now));
	}

}
