package dev.homeserver.smarthome.trusteddevice;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxAuthAge        registering a phone needs a login at most this old (claim {@code auth_time} in the JWT)
 * @param challengeLifetime how long a phone has to sign a challenge
 */
@ConfigurationProperties("smarthome.trusted-devices")
public record TrustedDeviceProperties(Duration maxAuthAge, Duration challengeLifetime) {

	public TrustedDeviceProperties {
		if (maxAuthAge == null) {
			maxAuthAge = Duration.ofMinutes(15);
		}
		if (challengeLifetime == null) {
			challengeLifetime = Duration.ofSeconds(60);
		}
	}

}
