package dev.homeserver.smarthome.kratos;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param adminUrl          Kratos admin API, only reachable inside the Docker network (e.g. {@code http://kratos:4434/})
 * @param setupCodeLifetime how long a setup code for a new account stays valid
 */
@ConfigurationProperties("smarthome.kratos")
public record KratosProperties(String adminUrl, Duration setupCodeLifetime) {

	public KratosProperties {
		if (setupCodeLifetime == null) {
			setupCodeLifetime = Duration.ofHours(24);
		}
	}

}
