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

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

	public ApiTokenService(ApiTokenRepository tokens, UserRepository users) {
		this.tokens = tokens;
		this.users = users;
	}

	public record CreatedToken(ApiToken token, String secret) {
	}

	@Transactional
	public CreatedToken create(long userId, String name) {
		User user = users.findById(userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User " + userId + " not found"));
		if (!user.isMachine()) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
					"Tokens are only for machine users, people sign in through Kratos");
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
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Token " + id + " not found");
		}
		tokens.deleteById(id);
	}

	/** The user behind a token, or empty if the token is unknown. */
	@Transactional
	public Optional<CurrentUser> authenticate(String secret) {
		return tokens.findByTokenHash(hash(secret)).map(token -> {
			token.setLastUsed(Instant.now());
			User user = users.findById(token.getUserId()).orElseThrow();
			if (!user.isActive()) {
				throw new UserDisabledException(user);
			}
			return CurrentUser.of(user, null);
		});
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
