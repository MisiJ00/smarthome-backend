package dev.homeserver.smarthome.notify;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.homeserver.smarthome.user.CurrentUser;

@RestController
public class NotificationController {

	private final NtfyProperties properties;
	private final NtfyTopics topics;

	public NotificationController(NtfyProperties properties, NtfyTopics topics) {
		this.properties = properties;
		this.topics = topics;
	}

	/**
	 * Where the caller subscribes for push messages (approval requests): in the ntfy app, server {@code server} and
	 * topic {@code topic}. The topic is the caller's own and secret. {@code enabled} is false when the server has no
	 * ntfy, and for machine users.
	 */
	@GetMapping("/api/me/notifications")
	public Subscription mine(@AuthenticationPrincipal CurrentUser me) {
		if (!properties.enabled() || me.identityId() == null) {
			return new Subscription(false, null, null);
		}
		return new Subscription(true, properties.publicUrl(), topics.of(me.id()));
	}

	public record Subscription(boolean enabled, String server, String topic) {
	}

}
