package dev.homeserver.smarthome.confirmation;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.homeserver.smarthome.trusteddevice.TrustedDeviceProperties;
import dev.homeserver.smarthome.error.ApiException;

/** Hands out challenges and uses them up. */
@Component
public class ChallengeStore {

	private final SecureRandom random = new SecureRandom();
	private final ChallengeRepository challenges;
	private final TrustedDeviceProperties properties;

	public ChallengeStore(ChallengeRepository challenges, TrustedDeviceProperties properties) {
		this.challenges = challenges;
		this.properties = properties;
	}

	@Transactional
	public ConfirmationChallenge create(long confirmationId, long userId) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		return challenges.save(new ConfirmationChallenge(confirmationId, userId, nonce,
				Instant.now().plus(properties.challengeLifetime())));
	}

	/**
	 * Uses up the challenge in its own transaction, before the signature is checked: a nonce works at most once, even
	 * when the attempt fails. 403 if it is unknown, for another confirmation or user, used or expired.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ConfirmationChallenge consume(long confirmationId, long userId, String nonce) {
		var challenge = challenges.findForUpdate(nonce == null ? "" : nonce)
				.filter(c -> c.getConfirmationId() == confirmationId && c.getUserId() == userId)
				.orElseThrow(() -> invalid("challenge.unknown",
						"Diese Freigabe-Anfrage ist unbekannt. Bitte noch einmal freigeben."));
		if (challenge.getUsedAt() != null) {
			throw invalid("challenge.used", "Diese Freigabe-Anfrage wurde schon benutzt. Bitte noch einmal freigeben.");
		}
		challenge.use();
		if (!challenge.getExpiresAt().isAfter(Instant.now())) {
			throw invalid("challenge.expired", "Die Freigabe-Anfrage ist abgelaufen. Bitte noch einmal freigeben.");
		}
		return challenge;
	}

	private static ApiException invalid(String code, String message) {
		return ApiException.forbidden(code, message);
	}

}
