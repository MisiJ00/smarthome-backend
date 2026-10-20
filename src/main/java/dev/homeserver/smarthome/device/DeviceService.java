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

import com.fasterxml.jackson.annotation.JsonProperty;

import dev.homeserver.smarthome.grant.Action;
import dev.homeserver.smarthome.grant.PermissionService;
import dev.homeserver.smarthome.grant.PermissionService.Access;
import dev.homeserver.smarthome.grant.PermissionService.Decision;
import dev.homeserver.smarthome.room.RoomRepository;
import dev.homeserver.smarthome.user.CurrentUser;

/**
 * Devices as a user sees them: lists only contain devices the user may view, devices the user may not view do not
 * exist for them (404). Adding, removing and locking actions is for admins (checked in the security config).
 */
@Service
public class DeviceService {

	private final DeviceRepository devices;
	private final RoomRepository rooms;
	private final PermissionService permissions;
	private final Map<String, DeviceAdapter> adapters;

	public DeviceService(DeviceRepository devices, RoomRepository rooms, PermissionService permissions,
			List<DeviceAdapter> adapters) {
		this.devices = devices;
		this.rooms = rooms;
		this.permissions = permissions;
		this.adapters = adapters.stream().collect(Collectors.toMap(DeviceAdapter::name, Function.identity()));
	}

	/** What a command led to: sent to the device, or waiting for a confirmation. */
	public enum CommandStatus {

		@JsonProperty("sent")
		SENT,

		@JsonProperty("confirmation_required")
		CONFIRMATION_REQUIRED

	}

	@Transactional(readOnly = true)
	public List<Device> list(CurrentUser user) {
		return visible(user, devices.findAll());
	}

	@Transactional(readOnly = true)
	public List<Device> listInRoom(CurrentUser user, long roomId) {
		if (!rooms.existsById(roomId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No room with id " + roomId);
		}
		return visible(user, devices.findByRoomId(roomId));
	}

	@Transactional(readOnly = true)
	public List<Device> listWithoutRoom(CurrentUser user) {
		return visible(user, devices.findByRoomIdIsNull());
	}

	/** The device if the user may view it, otherwise 404. */
	@Transactional(readOnly = true)
	public Device get(CurrentUser user, long id) {
		var device = get(id);
		if (!permissions.accessOf(user).canView(device)) {
			throw notFound(id);
		}
		return device;
	}

	@Transactional(readOnly = true)
	public Device get(long id) {
		return devices.findById(id).orElseThrow(() -> notFound(id));
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

	/**
	 * Null fields stay unchanged. With {@code setRoom} the device moves to {@code roomId}, null meaning no room. Name and
	 * room need {@code configure}, locked actions can only be changed by admins.
	 */
	@Transactional
	public Device update(CurrentUser user, long id, String name, boolean setRoom, Long roomId,
			List<String> lockedActions) {
		var device = get(user, id);
		var access = permissions.accessOf(user);
		if ((name != null || setRoom) && access.can(device, Action.CONFIGURE) != Decision.ALLOWED) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to configure " + device.getName());
		}
		if (lockedActions != null && !access.isAdmin()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can lock actions");
		}
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

	/**
	 * Locked actions are refused for everyone (403). Then {@code can()}: unlocking needs {@code unlock}, everything
	 * else {@code control}. If a confirmation is required, nothing is sent yet.
	 */
	public CommandStatus command(CurrentUser user, long id, Map<String, Object> command) {
		var device = get(user, id);
		CommandGuard.check(device, command);
		Action action = CommandGuard.permission(command);
		switch (permissions.can(user, device, action)) {
			case DENIED -> throw new CommandRefusedException(
					"Not allowed to " + action.dbValue() + " " + device.getName());
			case NEEDS_CONFIRMATION -> {
				return CommandStatus.CONFIRMATION_REQUIRED;
			}
			case ALLOWED -> {
			}
		}
		send(device, command);
		return CommandStatus.SENT;
	}

	void send(Device device, Map<String, Object> command) {
		adapter(device.getAdapter()).send(device.getExternalId(), command);
	}

	@EventListener
	@Transactional
	public void onStateChanged(DeviceStateChanged event) {
		devices.findByAdapterAndExternalId(event.adapter(), event.externalId())
				.ifPresent(device -> device.mergeState(event.state(), Instant.now()));
	}

	private List<Device> visible(CurrentUser user, List<Device> candidates) {
		Access access = permissions.accessOf(user);
		return candidates.stream().filter(access::canView).toList();
	}

	private static ResponseStatusException notFound(long id) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "No device with id " + id);
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
