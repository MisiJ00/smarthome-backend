package dev.homeserver.smarthome.device;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import dev.homeserver.smarthome.room.RoomRepository;

@Service
public class DeviceService {

	private final DeviceRepository devices;
	private final RoomRepository rooms;
	private final Map<String, DeviceAdapter> adapters;

	public DeviceService(DeviceRepository devices, RoomRepository rooms, List<DeviceAdapter> adapters) {
		this.devices = devices;
		this.rooms = rooms;
		this.adapters = adapters.stream().collect(Collectors.toMap(DeviceAdapter::name, Function.identity()));
	}

	@Transactional(readOnly = true)
	public List<Device> list() {
		return devices.findAll();
	}

	@Transactional(readOnly = true)
	public List<Device> listInRoom(long roomId) {
		if (!rooms.existsById(roomId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No room with id " + roomId);
		}
		return devices.findByRoomId(roomId);
	}

	@Transactional(readOnly = true)
	public List<Device> listWithoutRoom() {
		return devices.findByRoomIdIsNull();
	}

	@Transactional(readOnly = true)
	public Device get(long id) {
		return devices.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No device with id " + id));
	}

	/** Devices the adapters can see that have not been added yet. */
	@Transactional(readOnly = true)
	public List<DiscoveredDevice> discovered() {
		return adapters.values().stream()
				.flatMap(adapter -> adapter.discovered().stream())
				.filter(d -> !devices.existsByAdapterAndExternalId(d.adapter(), d.externalId()))
				.toList();
	}

	@Transactional
	public Device add(String adapter, String externalId, String name, Long roomId) {
		if (devices.existsByAdapterAndExternalId(adapter, externalId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Device has already been added");
		}
		if (roomId != null && !rooms.existsById(roomId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Room does not exist");
		}
		var discovered = adapter(adapter).discovered().stream()
				.filter(d -> d.externalId().equals(externalId))
				.findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"Adapter " + adapter + " does not see device " + externalId));
		return save(new Device(discovered, name, roomId));
	}

	/** Null fields stay unchanged. With {@code setRoom} the device moves to {@code roomId}, null meaning no room. */
	@Transactional
	public Device update(long id, String name, boolean setRoom, Long roomId, List<String> lockedActions) {
		var device = get(id);
		if (name != null) {
			device.setName(name);
		}
		if (setRoom) {
			if (roomId != null && !rooms.existsById(roomId)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Room does not exist");
			}
			device.setRoomId(roomId);
		}
		if (lockedActions != null) {
			device.setLockedActions(lockedActions);
		}
		return save(device);
	}

	@Transactional
	public void remove(long id) {
		devices.delete(get(id));
	}

	public void command(long id, Map<String, Object> command) {
		var device = get(id);
		CommandGuard.check(device, command);
		adapter(device.getAdapter()).send(device.getExternalId(), command);
	}

	@EventListener
	@Transactional
	public void onStateChanged(DeviceStateChanged event) {
		devices.findByAdapterAndExternalId(event.adapter(), event.externalId())
				.ifPresent(device -> device.mergeState(event.state(), Instant.now()));
	}

	private Device save(Device device) {
		try {
			return devices.saveAndFlush(device);
		}
		catch (DataIntegrityViolationException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Room does not exist");
		}
	}

	private DeviceAdapter adapter(String name) {
		var adapter = adapters.get(name);
		if (adapter == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown adapter " + name);
		}
		return adapter;
	}

}
