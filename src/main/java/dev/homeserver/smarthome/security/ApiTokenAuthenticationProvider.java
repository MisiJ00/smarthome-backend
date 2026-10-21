package dev.homeserver.smarthome.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import dev.homeserver.smarthome.token.ApiTokenService;

/** Bearer tokens starting with {@code shm_} are machine tokens; everything else is left to the JWT provider. */
public class ApiTokenAuthenticationProvider implements AuthenticationProvider {

	private final ApiTokenService tokens;

	public ApiTokenAuthenticationProvider(ApiTokenService tokens) {
		this.tokens = tokens;
	}

	@Override
	public Authentication authenticate(Authentication authentication) {
		String secret = ((BearerTokenAuthenticationToken) authentication).getToken();
		if (!secret.startsWith(ApiTokenService.PREFIX)) {
			return null;
		}
		return tokens.authenticate(secret)
				.map(login -> new UserAuthentication(login.user(), login.tokenId()))
				.orElseThrow(() -> new InvalidBearerTokenException("Unknown API token"));
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
	}

}
