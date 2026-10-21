package dev.homeserver.smarthome.notify;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Each user's ntfy topic: {@code sh_} plus 128 bits of an HMAC over the user id. Not guessable without the secret,
 * stable across restarts, and a new secret gives everyone new topics (e.g. after a leak).
 */
@Component
public class NtfyTopics {

	private final NtfyProperties properties;

	public NtfyTopics(NtfyProperties properties) {
		this.properties = properties;
	}

	public String of(long userId) {
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(properties.topicSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] hash = mac.doFinal(("smarthome-user:" + userId).getBytes(StandardCharsets.UTF_8));
			return "sh_" + HexFormat.of().formatHex(hash, 0, 16);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

}
