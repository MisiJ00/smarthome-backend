package dev.homeserver.smarthome.device;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.homeserver.smarthome.grant.Action;

/**
 * Safety rules that apply to every command, no matter who sends it.
 *
 * <p>A command is translated into action names (e.g. {@code switch_off}, {@code unlock},
 * {@code set_brightness}). If one of them is in the device's {@code locked_actions}, the
 * command is refused, for everyone including admins and the AI.
 *
 * <p>User and role permissions are checked after this ({@code PermissionService.can}); {@link #permission} says which
 * permission a command needs.
 */
public final class CommandGuard {

	private CommandGuard() {
	}

	public static Set<String> actions(Map<String, Object> command) {
		var actions = new LinkedHashSet<String>();
		command.forEach((key, value) -> {
			if (key.equals("state")) {
				switch (String.valueOf(value).toUpperCase(Locale.ROOT)) {
					case "ON" -> actions.add("switch_on");
					case "OFF" -> actions.add("switch_off");
					// A toggle can switch off, so it counts as both
					case "TOGGLE" -> {
						actions.add("switch_on");
						actions.add("switch_off");
					}
					case "LOCK" -> actions.add("lock");
					case "UNLOCK" -> actions.add("unlock");
					default -> actions.add("set_state");
				}
			}
			else {
				actions.add("set_" + key);
			}
		});
		return actions;
	}

	public static void check(Device device, Map<String, Object> command) {
		var actions = actions(command);
		for (var action : actions) {
			if (device.getLockedActions().contains(action)) {
				throw new CommandRefusedException("device.action_locked",
						"„" + actionName(action) + "“ ist bei " + device.getName() + " gesperrt.");
			}
		}
	}

	/** German name of an action for messages, e.g. {@code switch_off} → „Ausschalten“. */
	public static String actionName(String action) {
		return switch (action) {
			case "switch_on" -> "Einschalten";
			case "switch_off" -> "Ausschalten";
			case "lock" -> "Abschließen";
			case "unlock" -> "Aufschließen";
			default -> action.replaceFirst("^set_", "").replace('_', ' ') + " setzen";
		};
	}

	/** Unlocking needs the {@code unlock} permission, every other command {@code control}. */
	public static Action permission(Map<String, Object> command) {
		return actions(command).contains("unlock") ? Action.UNLOCK : Action.CONTROL;
	}

}
