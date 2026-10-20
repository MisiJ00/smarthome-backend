package dev.homeserver.smarthome.grant;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.grant.PermissionResolver.Permission;
import dev.homeserver.smarthome.user.Role;
import dev.homeserver.smarthome.user.User;
import dev.homeserver.smarthome.user.UserAdminService;

/** What a user may do, for the admin interface. Enforcing it ({@code can()}) comes with the device API. */
@Service
public class EffectivePermissionsService {

	private final UserAdminService users;
	private final GrantRepository grants;
	private final DeviceRepository devices;
	private final PermissionResolver resolver;
	private final GrantDescriber describer;

	public EffectivePermissionsService(UserAdminService users, GrantRepository grants, DeviceRepository devices,
			PermissionResolver resolver, GrantDescriber describer) {
		this.users = users;
		this.grants = grants;
		this.devices = devices;
		this.resolver = resolver;
		this.describer = describer;
	}

	/**
	 * @param grants  the user's own grants and those of its roles
	 * @param devices per device what the user may do (devices without any permission are left out)
	 */
	public record EffectivePermissions(long userId, boolean admin, boolean active, List<String> roles,
			List<GrantResponse> grants, List<DevicePermissions> devices) {
	}

	public record DevicePermissions(long deviceId, String name, Long roomId, String type, List<Permission> permissions,
			List<String> lockedActions) {
	}

	@Transactional(readOnly = true)
	public EffectivePermissions of(long userId) {
		User user = users.get(userId);
		boolean admin = user.hasRole(Role.ADMIN);
		List<Long> roleIds = user.getRoles().stream().map(Role::getId).toList();
		List<Grant> applicable = grants.findForUser(userId, roleIds.isEmpty() ? List.of(-1L) : roleIds);
		List<DevicePermissions> perDevice = devices.findAll().stream()
				.sorted(Comparator.comparing(Device::getName).thenComparing(Device::getId))
				.map(d -> new DevicePermissions(d.getId(), d.getName(), d.getRoomId(), d.getType(),
						resolver.resolve(admin, applicable, d), d.getLockedActions()))
				.filter(d -> !d.permissions().isEmpty())
				.toList();
		List<String> roleNames = user.getRoles().stream().map(Role::getName).sorted().toList();
		return new EffectivePermissions(user.getId(), admin, user.isActive(), roleNames, describer.describe(applicable),
				perDevice);
	}

}
