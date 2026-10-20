package dev.homeserver.smarthome.user;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import dev.homeserver.smarthome.kratos.KratosAdminClient;
import dev.homeserver.smarthome.kratos.KratosAdminClient.SetupCode;
import dev.homeserver.smarthome.kratos.KratosAdminClient.Traits;

/**
 * Managing users (admin only). People get a Kratos identity, which this service creates, changes and deletes through
 * the Kratos admin API; machine users (the AI) exist only here. There is always at least one active admin.
 */
@Service
public class UserAdminService {

	private static final int MAX_NAME_LENGTH = 100;

	private final UserRepository users;
	private final RoleRepository roles;
	private final KratosAdminClient kratos;

	public UserAdminService(UserRepository users, RoleRepository roles, KratosAdminClient kratos) {
		this.users = users;
		this.roles = roles;
		this.kratos = kratos;
	}

	public record CreatedUser(User user, SetupCode setupCode) {
	}

	@Transactional(readOnly = true)
	public List<User> list() {
		return users.findAllByOrderByNameAscIdAsc();
	}

	@Transactional(readOnly = true)
	public User get(long id) {
		return users.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No user with id " + id));
	}

	/**
	 * Creates the Kratos identity (without a password) and the user, and returns a setup code. The person enters the
	 * code in the app and then chooses a password.
	 */
	@Transactional
	public CreatedUser create(String email, String username, String name, List<String> roleNames) {
		String validName = validName(name);
		Set<Role> userRoles = rolesByName(roleNames);
		var identityId = kratos.createIdentity(new Traits(email, username, validName));
		try {
			User user = new User(identityId, validName, email, username);
			user.getRoles().addAll(userRoles);
			users.saveAndFlush(user);
			return new CreatedUser(user, kratos.createSetupCode(identityId));
		}
		catch (RuntimeException e) {
			// Do not leave an identity behind that the backend does not know
			kratos.deleteIdentity(identityId);
			throw e;
		}
	}

	/** A new setup code, e.g. when the first one expired or the person forgot the password (no email yet). */
	@Transactional(readOnly = true)
	public SetupCode newSetupCode(long id) {
		User user = get(id);
		if (user.isMachine()) {
			throw conflict("Machine users sign in with API tokens, not with a password");
		}
		if (!user.isActive()) {
			throw conflict("User " + id + " is deactivated");
		}
		return kratos.createSetupCode(user.getIdentityId());
	}

	/** Null fields stay unchanged. Deactivating signs the person out everywhere. */
	@Transactional
	public User update(long id, String name, Boolean active) {
		User user = get(id);
		if (active != null && active != user.isActive()) {
			if (!active) {
				ensureAnotherAdminRemains(user);
			}
			user.setActive(active);
		}
		if (name != null) {
			user.setName(validName(name));
		}
		users.flush();
		if (!user.isMachine()) {
			// Kratos last: if it fails, the local change is rolled back
			if (name != null) {
				kratos.rename(user.getIdentityId(), user.getName());
			}
			if (active != null) {
				kratos.setActive(user.getIdentityId(), active);
			}
		}
		return user;
	}

	/** Replaces the user's roles. */
	@Transactional
	public User setRoles(long id, List<String> roleNames) {
		User user = get(id);
		Set<Role> newRoles = rolesByName(roleNames);
		boolean losesAdmin = user.hasRole(Role.ADMIN) && newRoles.stream().noneMatch(r -> r.getName().equals(Role.ADMIN));
		if (losesAdmin) {
			ensureAnotherAdminRemains(user);
		}
		if (user.isMachine() && newRoles.stream().anyMatch(r -> r.getName().equals(Role.ADMIN))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Machine users cannot be admins");
		}
		user.getRoles().clear();
		user.getRoles().addAll(newRoles);
		return user;
	}

	/** Deletes the user together with its tokens, widgets and grants, and its Kratos identity. */
	@Transactional
	public void delete(long id) {
		User user = get(id);
		if (user.isMachine()) {
			throw conflict("Machine users cannot be deleted; revoke their tokens or deactivate them instead");
		}
		ensureAnotherAdminRemains(user);
		users.delete(user);
		users.flush();
		kratos.deleteIdentity(user.getIdentityId());
	}

	/** Refuses changes that would leave no active admin. */
	private void ensureAnotherAdminRemains(User user) {
		if (!user.isActive() || !user.hasRole(Role.ADMIN)) {
			return;
		}
		roles.lockAdminRole();
		if (users.countActiveAdmins() <= 1) {
			throw conflict("User " + user.getId() + " is the last active admin");
		}
	}

	private Set<Role> rolesByName(List<String> names) {
		Set<Role> result = new HashSet<>();
		for (String name : names == null ? List.<String>of() : names) {
			result.add(roles.findByName(name)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No role named " + name)));
		}
		return result;
	}

	private static String validName(String name) {
		String trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"name must have 1 to " + MAX_NAME_LENGTH + " characters");
		}
		return trimmed;
	}

	private static ResponseStatusException conflict(String message) {
		return new ResponseStatusException(HttpStatus.CONFLICT, message);
	}

}
