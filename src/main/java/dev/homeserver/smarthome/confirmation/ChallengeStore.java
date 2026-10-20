package dev.homeserver.smarthome.confirmation;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import dev.homeserver.smarthome.trusteddevice.TrustedDeviceProperties;

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
				.orElseThrow(() -> invalid("Unknown challenge"));
		if (challenge.getUsedAt() != null) {
			throw invalid("Challenge has already been used");
		}
		challenge.use();
		if (!challenge.getExpiresAt().isAfter(Instant.now())) {
			throw invalid("Challenge has expired");
		}
		return challenge;
	}

	private static ResponseStatusException invalid(String message) {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
	}

}
