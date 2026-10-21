package dev.homeserver.smarthome.events;

import java.security.Principal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.homeserver.smarthome.events.TicketService.IssuedTicket;
import dev.homeserver.smarthome.security.UserAuthentication;
import dev.homeserver.smarthome.user.CurrentUser;

@RestController
public class EventsController {

	private final TicketService tickets;

	public EventsController(TicketService tickets) {
		this.tickets = tickets;
	}

	/**
	 * A one-time ticket (valid 30 seconds) for opening {@code ws(s)://<host>/api/events?ticket=<ticket>}. Needed because
	 * browsers cannot send the bearer token with a WebSocket; apps use the same way.
	 */
	@PostMapping("/api/events/ticket")
	public IssuedTicket ticket(@AuthenticationPrincipal CurrentUser me, Principal principal) {
		Long apiTokenId = principal instanceof UserAuthentication auth ? auth.getApiTokenId() : null;
		return tickets.issue(me.id(), apiTokenId);
	}

}
