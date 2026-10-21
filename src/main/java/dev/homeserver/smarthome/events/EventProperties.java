package dev.homeserver.smarthome.events;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param ticketLifetime   how long a ticket for opening the connection is valid
 * @param maxConnectionAge connections are closed after this (code 4000), the client then connects with a new ticket
 * @param heartbeat        how often the server pings and rechecks every connection (user active, token valid)
 */
@ConfigurationProperties("smarthome.events")
public record EventProperties(Duration ticketLifetime, Duration maxConnectionAge, Duration heartbeat) {

	public EventProperties {
		if (ticketLifetime == null) {
			ticketLifetime = Duration.ofSeconds(30);
		}
		if (maxConnectionAge == null) {
			maxConnectionAge = Duration.ofHours(1);
		}
		if (heartbeat == null) {
			heartbeat = Duration.ofSeconds(30);
		}
	}

}
