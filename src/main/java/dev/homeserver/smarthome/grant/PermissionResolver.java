package dev.homeserver.smarthome.grant;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.homeserver.smarthome.device.Device;

/**
 * Turns grants into what a user may do with a device. Rules (see docs/smarthome-backend.md, "Rechteprüfung"):
 * <ul>
 * <li>Admins may do everything, without grants.</li>
 * <li>Otherwise the union of the user's own grants and those of its roles that match the device, its room or all.</li>
 * <li>If several grants allow the same action, the most permissive one wins: a confirmation is only needed when all
 * of them require one. Unlocking always needs a confirmation.</li>
 * <li>Any allowed action also allows viewing. Unlock only applies to locks.</li>
 * </ul>
 * Locked actions of a device ({@code devices.locked_actions}) are checked separately when a command is sent.
 */
@Component
public class PermissionResolver {

	public static final String LOCK_TYPE = "lock";

	/** One action a user may take on a device, and the grants that allow it (empty for admins). */
	public record Permission(Action action, boolean requiresConfirmation, List<Long> grantIds) {
	}

	public List<Permission> resolve(boolean admin, List<Grant> userGrants, Device device) {
		Map<Action, Permission> result = new EnumMap<>(Action.class);
		for (Action action : Action.values()) {
			if (action == Action.UNLOCK && !LOCK_TYPE.equals(device.getType())) {
				continue;
			}
			if (admin) {
				result.put(action, new Permission(action, action == Action.UNLOCK, List.of()));
				continue;
			}
			List<Grant> matching = userGrants.stream()
					.filter(g -> g.getAction() == action && matches(g, device))
					.toList();
			if (!matching.isEmpty()) {
				boolean confirm = action == Action.UNLOCK || matching.stream().allMatch(Grant::isRequiresConfirmation);
				result.put(action, new Permission(action, confirm, matching.stream().map(Grant::getId).toList()));
			}
		}
		if (!result.isEmpty() && !result.containsKey(Action.VIEW)) {
			List<Long> via = new ArrayList<>();
			result.values().forEach(p -> via.addAll(p.grantIds()));
			result.put(Action.VIEW, new Permission(Action.VIEW, false, via.stream().distinct().sorted().toList()));
		}
		return List.copyOf(result.values());
	}

	static boolean matches(Grant grant, Device device) {
		return switch (grant.getScopeType()) {
			case ALL -> true;
			case ROOM -> device.getRoomId() != null && device.getRoomId().equals(grant.getRoomId());
			case DEVICE -> device.getId().equals(grant.getDeviceId());
		};
	}

}
