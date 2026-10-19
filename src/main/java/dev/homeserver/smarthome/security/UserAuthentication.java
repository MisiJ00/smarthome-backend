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

	/** A machine user signed in with an API token. */
	public UserAuthentication(CurrentUser user) {
		this(user, null);
	}

	public UserAuthentication(CurrentUser user, Jwt jwt) {
		super(user.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
		this.user = user;
		this.jwt = jwt;
		setAuthenticated(true);
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
