package dev.homeserver.smarthome.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import dev.homeserver.smarthome.user.CurrentUser;

/**
 * An authenticated request. The principal is the backend's own user, roles become {@code ROLE_<name>}. The credentials
 * are the Kratos JWT, or null for machine tokens.
 */
public class UserAuthentication extends AbstractAuthenticationToken {

	private final CurrentUser user;
	private final Jwt jwt;
	private final Long apiTokenId;

	/** A machine user signed in with an API token. */
	public UserAuthentication(CurrentUser user, long apiTokenId) {
		this(user, null, apiTokenId);
	}

	public UserAuthentication(CurrentUser user, Jwt jwt) {
		this(user, jwt, null);
	}

	private UserAuthentication(CurrentUser user, Jwt jwt, Long apiTokenId) {
		super(user.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
		this.user = user;
		this.jwt = jwt;
		this.apiTokenId = apiTokenId;
		setAuthenticated(true);
	}

	/** The machine token used for this request, null for Kratos JWTs. */
	public Long getApiTokenId() {
		return apiTokenId;
	}

	@Override
	public CurrentUser getPrincipal() {
		return user;
	}

	@Override
	public Jwt getCredentials() {
		return jwt;
	}

	@Override
	public String getName() {
		return String.valueOf(user.id());
	}

}
