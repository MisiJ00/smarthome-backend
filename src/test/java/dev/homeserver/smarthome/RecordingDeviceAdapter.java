package dev.homeserver.smarthome;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.homeserver.smarthome.device.DeviceAdapter;
import dev.homeserver.smarthome.device.DiscoveredDevice;

/** Adapter {@code test}: records the commands it gets instead of talking to a device. */
public class RecordingDeviceAdapter implements DeviceAdapter {

	public static final String NAME = "test";

	public record SentCommand(String externalId, Map<String, Object> command) {
	}

	private final List<SentCommand> sent = new CopyOnWriteArrayList<>();

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Collection<DiscoveredDevice> discovered() {
		return List.of();
	}

	@Override
	public void send(String externalId, Map<String, Object> command) {
		sent.add(new SentCommand(externalId, Map.copyOf(command)));
	}

	/** Commands sent to one device, oldest first. */
	public List<Map<String, Object>> sentTo(String externalId) {
		return sent.stream().filter(c -> c.externalId().equals(externalId)).map(SentCommand::command).toList();
	}

}
