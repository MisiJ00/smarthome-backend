package dev.homeserver.smarthome.room;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.device.DeviceRepository.RoomDeviceCount;
import dev.homeserver.smarthome.user.CurrentUser;

@Service
public class RoomService {

	static final int MAX_NAME_LENGTH = 64;
	static final int MAX_ICON_LENGTH = 32;

	private final RoomRepository rooms;
	private final DeviceRepository devices;

	public RoomService(RoomRepository rooms, DeviceRepository devices) {
		this.rooms = rooms;
		this.devices = devices;
	}

	public record RoomWithCount(Room room, long deviceCount) {
	}

	/**
	 * All rooms in display order with their number of devices. Takes the caller so that rooms can be filtered by
	 * permissions once {@code can()} exists; until then everyone signed in sees all rooms.
	 */
	@Transactional(readOnly = true)
	public List<RoomWithCount> list(CurrentUser user) {
		Map<Long, Long> counts = devices.countPerRoom().stream()
				.collect(Collectors.toMap(RoomDeviceCount::roomId, RoomDeviceCount::deviceCount));
		return rooms.findAllByOrderBySortAscNameAsc().stream()
				.map(room -> new RoomWithCount(room, counts.getOrDefault(room.getId(), 0L)))
				.toList();
	}

	@Transactional(readOnly = true)
	public RoomWithCount get(long id) {
		return new RoomWithCount(find(id), devices.countByRoomId(id));
	}

	/** New rooms go to the end of the list. */
	@Transactional
	public Room create(String name, String icon) {
		String trimmed = validName(name);
		if (rooms.existsByNameIgnoreCase(trimmed)) {
			throw nameTaken(trimmed);
		}
		return save(new Room(trimmed, validIcon(icon), rooms.nextSort()), trimmed);
	}

	/** Null fields stay unchanged, except the icon: {@code clearIcon} removes it. */
	@Transactional
	public RoomWithCount update(long id, String name, String icon, boolean clearIcon, Integer sort) {
		Room room = find(id);
		if (name != null) {
			String trimmed = validName(name);
			if (rooms.existsByNameIgnoreCaseAndIdNot(trimmed, id)) {
				throw nameTaken(trimmed);
			}
			room.setName(trimmed);
		}
		if (clearIcon) {
			room.setIcon(null);
		}
		else if (icon != null) {
			room.setIcon(validIcon(icon));
		}
		if (sort != null) {
			room.setSort(sort);
		}
		save(room, room.getName());
		return new RoomWithCount(room, devices.countByRoomId(id));
	}

	/** Sets the display order. The list has to contain every room exactly once. */
	@Transactional
	public void reorder(List<Long> roomIds) {
		List<Room> all = rooms.findAll();
		var ids = all.stream().map(Room::getId).collect(Collectors.toSet());
		if (roomIds.size() != ids.size() || !new HashSet<>(roomIds).equals(ids)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomIds must contain every room exactly once");
		}
		Map<Long, Room> byId = all.stream().collect(Collectors.toMap(Room::getId, room -> room));
		for (int i = 0; i < roomIds.size(); i++) {
			Room room = byId.get(roomIds.get(i));
			if (room.getSort() != i) {
				room.setSort(i);
			}
		}
	}

	@Transactional
	public void delete(long id) {
		Room room = find(id);
		long deviceCount = devices.countByRoomId(id);
		if (deviceCount > 0) {
			throw new RoomNotEmptyException(id, deviceCount);
		}
		try {
			rooms.delete(room);
			rooms.flush();
		}
		catch (DataIntegrityViolationException e) {
			// A device was put in the room in the meantime
			throw new RoomNotEmptyException(id, devices.countByRoomId(id));
		}
	}

	private Room find(long id) {
		return rooms.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No room with id " + id));
	}

	private Room save(Room room, String name) {
		try {
			return rooms.saveAndFlush(room);
		}
		catch (DataIntegrityViolationException e) {
			// Same name created in parallel; the unique index on lower(name) caught it
			throw nameTaken(name);
		}
	}

	private static String validName(String name) {
		String trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank");
		}
		if (trimmed.length() > MAX_NAME_LENGTH) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"name must be at most " + MAX_NAME_LENGTH + " characters");
		}
		return trimmed;
	}

	private static String validIcon(String icon) {
		if (icon == null || icon.isBlank()) {
			return null;
		}
		String trimmed = icon.strip();
		if (trimmed.length() > MAX_ICON_LENGTH) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"icon must be at most " + MAX_ICON_LENGTH + " characters");
		}
		return trimmed;
	}

	private static ResponseStatusException nameTaken(String name) {
		return new ResponseStatusException(HttpStatus.CONFLICT, "A room named \"" + name + "\" already exists");
	}

}
