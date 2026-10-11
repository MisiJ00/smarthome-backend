package dev.homeserver.smarthome.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CommandGuardTest {

	@Test
	void translatesCommandsIntoActions() {
		assertThat(CommandGuard.actions(Map.of("state", "on"))).containsExactly("switch_on");
		assertThat(CommandGuard.actions(Map.of("state", "TOGGLE"))).containsExactlyInAnyOrder("switch_on", "switch_off");
		assertThat(CommandGuard.actions(Map.of("brightness", 100))).containsExactly("set_brightness");
	}

	@Test
	void refusesLockedActionsEvenForToggle() {
		var serverPlug = device("switch", List.of("switch_off"));

		assertThatThrownBy(() -> CommandGuard.check(serverPlug, Map.of("state", "OFF")))
				.isInstanceOf(CommandRefusedException.class);
		assertThatThrownBy(() -> CommandGuard.check(serverPlug, Map.of("state", "toggle")))
				.isInstanceOf(CommandRefusedException.class);
		assertThatCode(() -> CommandGuard.check(serverPlug, Map.of("state", "ON"))).doesNotThrowAnyException();
	}

	@Test
	void refusesUnlockUntilConfirmationsExist() {
		var lock = device("lock", List.of());

		assertThatThrownBy(() -> CommandGuard.check(lock, Map.of("state", "UNLOCK")))
				.isInstanceOf(CommandRefusedException.class);
		assertThatCode(() -> CommandGuard.check(lock, Map.of("state", "LOCK"))).doesNotThrowAnyException();
	}

	private static Device device(String type, List<String> lockedActions) {
		var discovered = new DiscoveredDevice("z2m", "0x1", "Test", type, null, null, null, Map.of(), Map.of());
		var device = new Device(discovered, "Test", null);
		device.setLockedActions(lockedActions);
		return device;
	}

}
