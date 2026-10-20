package dev.homeserver.smarthome.grant;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.room.Room;
import dev.homeserver.smarthome.room.RoomRepository;
import dev.homeserver.smarthome.user.Role;
import dev.homeserver.smarthome.user.RoleRepository;
import dev.homeserver.smarthome.user.User;
import dev.homeserver.smarthome.user.UserRepository;

/** Adds the names of roles, users, rooms and devices to grants. All of these tables are small. */
@Component
public class GrantDescriber {

	private final RoleRepository roles;
	private final UserRepository users;
	private final RoomRepository rooms;
	private final DeviceRepository devices;

	public GrantDescriber(RoleRepository roles, UserRepository users, RoomRepository rooms, DeviceRepository devices) {
		this.roles = roles;
		this.users = users;
		this.rooms = rooms;
		this.devices = devices;
	}

	@Transactional(readOnly = true)
	public List<GrantResponse> describe(List<Grant> grants) {
		Map<Long, String> roleNames = roles.findAll().stream().collect(Collectors.toMap(Role::getId, Role::getName));
		Map<Long, String> userNames = users.findAll().stream().collect(Collectors.toMap(User::getId, User::getName));
		Map<Long, String> roomNames = rooms.findAll().stream().collect(Collectors.toMap(Room::getId, Room::getName));
		Map<Long, String> deviceNames = devices.findAll().stream()
				.collect(Collectors.toMap(Device::getId, Device::getName));
		Function<Grant, String> subjectName = g -> g.getRoleId() != null ? roleNames.get(g.getRoleId())
				: userNames.get(g.getUserId());
		Function<Grant, String> scopeName = g -> switch (g.getScopeType()) {
			case ALL -> null;
			case ROOM -> roomNames.get(g.getRoomId());
			case DEVICE -> deviceNames.get(g.getDeviceId());
		};
		return grants.stream()
				.map(g -> new GrantResponse(g.getId(), g.getSubjectType(), g.getSubjectId(), subjectName.apply(g),
						g.getScopeType(), g.getScopeId(), scopeName.apply(g), g.getAction(), g.isRequiresConfirmation(),
						g.getCreatedAt()))
				.toList();
	}

	public GrantResponse describe(Grant grant) {
		return describe(List.of(grant)).getFirst();
	}

}
