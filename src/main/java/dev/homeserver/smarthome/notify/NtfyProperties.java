package dev.homeserver.smarthome.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Push messages over ntfy. Without {@code url} and {@code topicSecret} nothing is sent.
 *
 * @param url         where the backend sends to (inside the Docker network, e.g. {@code http://ntfy:80})
 * @param publicUrl   where the phones reach ntfy; shown to the user for subscribing
 * @param token       ntfy access token of the backend (may publish to {@code sh_*})
 * @param topicSecret key from which each user's topic is derived; whoever knows a topic can read it
 */
@ConfigurationProperties("smarthome.ntfy")
public record NtfyProperties(String url, String publicUrl, String token, String topicSecret) {

	public boolean enabled() {
		return url != null && !url.isBlank() && topicSecret != null && !topicSecret.isBlank();
	}

}
