package dev.homeserver.smarthome.device;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Safety rules that apply to every command, no matter who sends it.
 *
 * <p>A command is translated into action names (e.g. {@code switch_off}, {@code unlock},
 * {@code set_brightness}). If one of them is in the device's {@code locked_actions}, the
 * command is refused. Unlocking is refused for now, until the confirmation flow exists.
 *
 * <p>User and role permissions come later and will sit in front of this.
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
				throw new CommandRefusedException("'" + action + "' is locked for device " + device.getName());
			}
		}
		if (actions.contains("unlock")) {
			throw new CommandRefusedException("Unlocking needs a confirmation, which is not implemented yet");
		}
	}

}
