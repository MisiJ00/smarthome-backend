package dev.homeserver.smarthome.device;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dev.homeserver.smarthome.error.ApiException;

/**
 * Hands a command to the device's adapter. No checks here: callers ({@code DeviceService}, approved confirmations)
 * check locked actions and permissions first.
 */
@Component
public class DeviceCommandSender {

	private final Map<String, DeviceAdapter> adapters;

	public DeviceCommandSender(List<DeviceAdapter> adapters) {
		this.adapters = adapters.stream().collect(Collectors.toMap(DeviceAdapter::name, Function.identity()));
	}

	public void send(Device device, Map<String, Object> command) {
		adapter(device.getAdapter()).send(device.getExternalId(), command);
	}

	DeviceAdapter adapter(String name) {
		var adapter = adapters.get(name);
		if (adapter == null) {
			throw ApiException.badRequest("adapter.unknown", "Den Adapter „" + name + "“ gibt es nicht.");
		}
		return adapter;
	}

	List<DeviceAdapter> all() {
		return List.copyOf(adapters.values());
	}

}
