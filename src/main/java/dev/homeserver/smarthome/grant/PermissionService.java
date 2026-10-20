package dev.homeserver.smarthome.grant;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.grant.PermissionResolver.Permission;
import dev.homeserver.smarthome.user.CurrentUser;
import dev.homeserver.smarthome.user.Role;
import dev.homeserver.smarthome.user.RoleRepository;

/**
 * The permission check {@code can(user, device, action)} (rules: {@link PermissionResolver}). Locked actions of a
 * device are checked before this, for everyone, by {@code CommandGuard}.
 */
@Service
public class PermissionService {

	public enum Decision {
		ALLOWED, NEEDS_CONFIRMATION, DENIED
	}

	private final GrantRepository grants;
	private final RoleRepository roles;
	private final PermissionResolver resolver;

	public PermissionService(GrantRepository grants, RoleRepository roles, PermissionResolver resolver) {
		this.grants = grants;
		this.roles = roles;
		this.resolver = resolver;
	}

	public Decision can(CurrentUser user, Device device, Action action) {
		return accessOf(user).can(device, action);
	}

	/** Loads the user's grants once, for checking many devices (e.g. the device list). */
	@Transactional(readOnly = true)
	public Access accessOf(CurrentUser user) {
		boolean admin = user.roles().contains(Role.ADMIN);
		if (admin) {
			return new Access(true, List.of(), resolver);
		}
		List<Long> roleIds = roles.findAll().stream()
				.filter(role -> user.roles().contains(role.getName()))
				.map(Role::getId)
				.toList();
		return new Access(false, grants.findForUser(user.id(), roleIds.isEmpty() ? List.of(-1L) : roleIds), resolver);
	}

	/** What one user may do. */
	public static final class Access {

		private final boolean admin;
		private final List<Grant> grants;
		private final PermissionResolver resolver;

		Access(boolean admin, List<Grant> grants, PermissionResolver resolver) {
			this.admin = admin;
			this.grants = grants;
			this.resolver = resolver;
		}

		public boolean isAdmin() {
			return admin;
		}

		public List<Permission> permissions(Device device) {
			return resolver.resolve(admin, grants, device);
		}

		public boolean canView(Device device) {
			return can(device, Action.VIEW) != Decision.DENIED;
		}

		public Decision can(Device device, Action action) {
			return permissions(device).stream()
					.filter(p -> p.action() == action)
					.findFirst()
					.map(p -> p.requiresConfirmation() ? Decision.NEEDS_CONFIRMATION : Decision.ALLOWED)
					.orElse(Decision.DENIED);
		}

	}

}
