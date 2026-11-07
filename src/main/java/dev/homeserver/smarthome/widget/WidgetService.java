package dev.homeserver.smarthome.widget;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceController.DevicePermission;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.events.LiveEvents.WidgetsChanged;
import dev.homeserver.smarthome.grant.Action;
import dev.homeserver.smarthome.grant.PermissionService;
import dev.homeserver.smarthome.grant.PermissionService.Access;
import dev.homeserver.smarthome.room.RoomService;
import dev.homeserver.smarthome.room.RoomService.RoomWithCount;
import dev.homeserver.smarthome.user.CurrentUser;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everyone's own widgets. A widget can only be created for a device or room the user may view; if that right goes
 * away later, the widget stays but shows up as unavailable, without name or state.
 */
@Service
public class WidgetService {

	static final int MAX_WIDGETS = 50;
	static final int MAX_CONFIG_BYTES = 2048;

	private final WidgetRepository widgets;
	private final DeviceRepository devices;
	private final RoomService rooms;
	private final PermissionService permissions;
	private final ApplicationEventPublisher events;
	private final JsonMapper json;

	public WidgetService(WidgetRepository widgets, DeviceRepository devices, RoomService rooms,
			PermissionService permissions, ApplicationEventPublisher events, JsonMapper json) {
		this.widgets = widgets;
		this.devices = devices;
		this.rooms = rooms;
		this.permissions = permissions;
		this.events = events;
		this.json = json;
	}

	/**
	 * One widget for the homescreen. Unavailable widgets only carry their id, kind and target. Fields that do not
	 * apply are left out to keep the answer small.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WidgetData(long id, WidgetKind kind, TargetType targetType, long targetId, boolean available,
			String name, Long roomId, Map<String, Object> state, List<DevicePermission> actions, Integer deviceCount,
			Integer onCount, Map<String, Object> config) {
	}

	public record HomescreenData(Instant generatedAt, List<WidgetData> widgets) {
	}

	@Transactional(readOnly = true)
	public List<Widget> list(CurrentUser user) {
		return widgets.findByUserIdOrderByPositionAscIdAsc(user.id());
	}

	/** New widgets go to the end. */
	@Transactional
	public Widget create(CurrentUser user, WidgetKind kind, TargetType targetType, long targetId,
			Map<String, Object> config) {
		if (widgets.countByUserId(user.id()) >= MAX_WIDGETS) {
			throw badRequest("At most " + MAX_WIDGETS + " widgets");
		}
		checkTarget(user, kind, targetType, targetId);
		var widget = widgets.save(new Widget(user.id(), kind, targetType, targetId, validConfig(config),
				widgets.nextPosition(user.id())));
		changed(user);
		return widget;
	}

	/** Kind and config can change; the target cannot (create a new widget instead). */
	@Transactional
	public Widget update(CurrentUser user, long id, WidgetKind kind, Map<String, Object> config) {
		var widget = own(user, id);
		if (kind != null) {
			checkTarget(user, kind, widget.getTargetType(), widget.getTargetId());
			widget.setKind(kind);
		}
		if (config != null) {
			widget.setConfig(validConfig(config));
		}
		changed(user);
		return widget;
	}

	/** New order; has to contain each of the user's widgets exactly once. */
	@Transactional
	public void reorder(CurrentUser user, List<Long> widgetIds) {
		var mine = widgets.findByUserIdOrderByPositionAscIdAsc(user.id());
		var ids = mine.stream().map(Widget::getId).collect(Collectors.toSet());
		if (widgetIds.size() != ids.size() || !new HashSet<>(widgetIds).equals(ids)) {
			throw badRequest("widgetIds must contain each of your widgets exactly once");
		}
		Map<Long, Widget> byId = mine.stream().collect(Collectors.toMap(Widget::getId, Function.identity()));
		for (int i = 0; i < widgetIds.size(); i++) {
			byId.get(widgetIds.get(i)).setPosition(i);
		}
		changed(user);
	}

	@Transactional
	public void delete(CurrentUser user, long id) {
		widgets.delete(own(user, id));
		changed(user);
	}

	/** Everything the homescreen needs in one small answer, in widget order. */
	@Transactional(readOnly = true)
	public HomescreenData data(CurrentUser user) {
		Access access = permissions.accessOf(user);
		var result = widgets.findByUserIdOrderByPositionAscIdAsc(user.id()).stream()
				.map(w -> w.getTargetType() == TargetType.ROOM ? roomData(user, access, w) : deviceData(access, w))
				.toList();
		return new HomescreenData(Instant.now(), result);
	}

	private WidgetData deviceData(Access access, Widget w) {
		var device = devices.findById(w.getTargetId()).filter(access::canView).orElse(null);
		if (device == null) {
			return unavailable(w);
		}
		var actions = access.permissions(device).stream()
				.filter(p -> p.action() != Action.VIEW)
				.map(p -> new DevicePermission(p.action(), p.requiresConfirmation()))
				.toList();
		return new WidgetData(w.getId(), w.getKind(), w.getTargetType(), w.getTargetId(), true, device.getName(),
				device.getRoomId(), state(w.getKind(), device), actions, null, null, w.getConfig());
	}

	private WidgetData roomData(CurrentUser user, Access access, Widget w) {
		RoomWithCount room;
		try {
			room = rooms.get(user, w.getTargetId());
		}
		catch (ResponseStatusException e) {
			return unavailable(w);
		}
		var visible = devices.findByRoomId(w.getTargetId()).stream().filter(access::canView).toList();
		int on = (int) visible.stream().filter(d -> "ON".equals(d.getState().get("state"))).count();
		return new WidgetData(w.getId(), w.getKind(), w.getTargetType(), w.getTargetId(), true, room.room().getName(),
				null, null, null, visible.size(), on, w.getConfig());
	}

	private static WidgetData unavailable(Widget w) {
		return new WidgetData(w.getId(), w.getKind(), w.getTargetType(), w.getTargetId(), false, null, null, null, null,
				null, null, null);
	}

	private static Map<String, Object> state(WidgetKind kind, Device device) {
		if (kind.state().isEmpty()) {
			return device.getState();
		}
		var picked = new LinkedHashMap<String, Object>();
		kind.state().forEach(key -> {
			if (device.getState().containsKey(key)) {
				picked.put(key, device.getState().get(key));
			}
		});
		return picked;
	}

	private void checkTarget(CurrentUser user, WidgetKind kind, TargetType targetType, long targetId) {
		switch (targetType) {
			case SCENE -> throw badRequest("Scenes are not available yet");
			case ROOM -> {
				try {
					rooms.get(user, targetId);
				}
				catch (ResponseStatusException e) {
					throw badRequest("No room with id " + targetId);
				}
			}
			case DEVICE -> {
				var device = devices.findById(targetId)
						.filter(d -> permissions.accessOf(user).canView(d))
						.orElseThrow(() -> badRequest("No device with id " + targetId));
				if (kind.target() == TargetType.DEVICE && !kind.fits(device.getType())) {
					throw badRequest("A " + kind.dbValue() + " widget does not fit a " + device.getType());
				}
			}
		}
		if (kind.target() != targetType) {
			throw badRequest("A " + kind.dbValue() + " widget needs a " + kind.target().dbValue());
		}
	}

	private Map<String, Object> validConfig(Map<String, Object> config) {
		if (config == null) {
			return Map.of();
		}
		if (json.writeValueAsBytes(config).length > MAX_CONFIG_BYTES) {
			throw badRequest("config must be at most " + MAX_CONFIG_BYTES + " bytes");
		}
		return config;
	}

	private Widget own(CurrentUser user, long id) {
		return widgets.findById(id).filter(w -> w.getUserId() == user.id())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No widget with id " + id));
	}

	private void changed(CurrentUser user) {
		events.publishEvent(new WidgetsChanged(user.id()));
	}

	private static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

}
