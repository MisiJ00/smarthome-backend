package dev.homeserver.smarthome.confirmation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param lifetime how long a confirmation waits for a decision before it expires */
@ConfigurationProperties("smarthome.confirmations")
public record ConfirmationProperties(Duration lifetime) {

	public ConfirmationProperties {
		if (lifetime == null) {
			lifetime = Duration.ofMinutes(5);
		}
	}

}
