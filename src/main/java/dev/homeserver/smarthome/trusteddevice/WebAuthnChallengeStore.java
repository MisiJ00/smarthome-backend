package dev.homeserver.smarthome.trusteddevice;

import java.security.SecureRandom;
import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.homeserver.smarthome.error.ApiException;

/** Hands out the challenges for creating a browser credential and uses them up. */
@Component
class WebAuthnChallengeStore {

	private final SecureRandom random = new SecureRandom();
	private final WebAuthnChallengeRepository challenges;
	private final TrustedDeviceProperties properties;

	WebAuthnChallengeStore(WebAuthnChallengeRepository challenges, TrustedDeviceProperties properties) {
		this.challenges = challenges;
		this.properties = properties;
	}

	@Transactional
	WebAuthnChallenge create(long userId) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return challenges.save(new WebAuthnChallenge(userId, WebAuthn.base64url(bytes),
				Instant.now().plus(properties.challengeLifetime())));
	}

	/** Uses the challenge up in its own transaction, before anything is checked: it works at most once. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void consume(long userId, String challenge) {
		var found = challenges.findForUpdate(challenge == null ? "" : challenge)
				.filter(c -> c.getUserId() == userId)
				.orElseThrow(() -> ApiException.forbidden("webauthn.challenge_unknown",
						"Diese Anfrage ist unbekannt. Bitte noch einmal versuchen."));
		if (found.getUsedAt() != null) {
			throw ApiException.forbidden("webauthn.challenge_used",
					"Diese Anfrage wurde schon benutzt. Bitte noch einmal versuchen.");
		}
		found.use();
		if (!found.getExpiresAt().isAfter(Instant.now())) {
			throw ApiException.forbidden("webauthn.challenge_expired",
					"Die Anfrage ist abgelaufen. Bitte noch einmal versuchen.");
		}
	}

}
