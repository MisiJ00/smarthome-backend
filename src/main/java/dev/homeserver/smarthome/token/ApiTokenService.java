package dev.homeserver.smarthome.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.homeserver.smarthome.events.LiveEvents.TokenRevoked;
import dev.homeserver.smarthome.error.ApiException;
import dev.homeserver.smarthome.user.CurrentUser;
import dev.homeserver.smarthome.user.User;
import dev.homeserver.smarthome.user.UserDisabledException;
import dev.homeserver.smarthome.user.UserRepository;

/**
 * Machine tokens for the AI and scripts. Tokens are long random strings, so a plain SHA-256 hash is enough to store
 * them. Humans never get one, they sign in through Kratos.
 */
@Service
public class ApiTokenService {

	/** Lets the backend tell machine tokens apart from Kratos JWTs. */
	public static final String PREFIX = "shm_";

	private final SecureRandom random = new SecureRandom();
	private final ApiTokenRepository tokens;
	private final UserRepository users;
	private final ApplicationEventPublisher events;

	public ApiTokenService(ApiTokenRepository tokens, UserRepository users, ApplicationEventPublisher events) {
		this.tokens = tokens;
		this.users = users;
		this.events = events;
	}

	public record CreatedToken(ApiToken token, String secret) {
	}

	/** A machine user signed in with one of its tokens. */
	public record TokenLogin(CurrentUser user, long tokenId) {
	}

	@Transactional
	public CreatedToken create(long userId, String name) {
		User user = users.findById(userId)
				.orElseThrow(() -> ApiException.notFound("user.not_found", "Diesen Benutzer gibt es nicht."));
		if (!user.isMachine()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "token.people_not_allowed",
					"Tokens gibt es nur für Maschinen-User; Menschen melden sich mit ihrem Konto an.");
		}
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		return new CreatedToken(tokens.save(new ApiToken(userId, name, hash(secret))), secret);
	}

	@Transactional(readOnly = true)
	public List<CurrentUser> machineUsers() {
		return users.findAll().stream().filter(User::isMachine).map(user -> CurrentUser.of(user, null)).toList();
	}

	@Transactional(readOnly = true)
	public List<ApiToken> list() {
		return tokens.findAll();
	}

	@Transactional
	public void revoke(long id) {
		if (!tokens.existsById(id)) {
			throw ApiException.notFound("token.not_found", "Dieses Token gibt es nicht.");
		}
		tokens.deleteById(id);
		events.publishEvent(new TokenRevoked(id));
	}

	/** The user behind a token, or empty if the token is unknown. */
	@Transactional
	public Optional<TokenLogin> authenticate(String secret) {
		return tokens.findByTokenHash(hash(secret)).map(token -> {
			token.setLastUsed(Instant.now());
			User user = users.findById(token.getUserId()).orElseThrow();
			if (!user.isActive()) {
				throw new UserDisabledException(user);
			}
			return new TokenLogin(CurrentUser.of(user, null), token.getId());
		});
	}

	@Transactional(readOnly = true)
	public boolean exists(long tokenId) {
		return tokens.existsById(tokenId);
	}

	private static String hash(String secret) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
