package dev.homeserver.smarthome.user;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.homeserver.smarthome.security.AuthProperties;

@Service
public class UserService {

	private final UserRepository users;
	private final RoleRepository roles;
	private final AuthProperties properties;

	public UserService(UserRepository users, RoleRepository roles, AuthProperties properties) {
		this.users = users;
		this.roles = roles;
		this.properties = properties;
	}

	/**
	 * Returns the user for a Kratos identity, creating it on its first request. Keeps the name in sync with Kratos
	 * and makes sure the initial admin from the configuration has the admin role.
	 */
	@Transactional
	public CurrentUser signIn(UUID identityId, String name, String email) {
		users.insertIfMissing(identityId, name);
		User user = users.findByIdentityId(identityId).orElseThrow();
		if (!name.equals(user.getName())) {
			user.setName(name);
		}
		if (isInitialAdmin(identityId, email) && !user.hasRole(Role.ADMIN)) {
			user.getRoles().add(roles.findByName(Role.ADMIN).orElseThrow());
		}
		if (!user.isActive()) {
			throw new UserDisabledException(identityId);
		}
		Set<String> roleNames = user.getRoles().stream().map(Role::getName).collect(Collectors.toUnmodifiableSet());
		return new CurrentUser(user.getId(), identityId, user.getName(), email, roleNames);
	}

	private boolean isInitialAdmin(UUID identityId, String email) {
		String admin = properties.initialAdmin();
		if (admin == null || admin.isBlank()) {
			return false;
		}
		return admin.equalsIgnoreCase(identityId.toString()) || (email != null && admin.equalsIgnoreCase(email));
	}

}
