package dev.homeserver.smarthome.events;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

import dev.homeserver.smarthome.confirmation.ConfirmationRepository;
import dev.homeserver.smarthome.confirmation.ConfirmationService;
import dev.homeserver.smarthome.confirmation.ConfirmationStatus;
import dev.homeserver.smarthome.confirmation.ConfirmationViews;
import dev.homeserver.smarthome.device.DeviceController.DeviceResponse;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.events.EventHub.Connection;
import dev.homeserver.smarthome.events.LiveEvents.ConfirmationChanged;
import dev.homeserver.smarthome.events.LiveEvents.DeviceChanged;
import dev.homeserver.smarthome.events.LiveEvents.DeviceDiscovered;
import dev.homeserver.smarthome.events.LiveEvents.DeviceRemoved;
import dev.homeserver.smarthome.events.LiveEvents.DeviceStateUpdated;
import dev.homeserver.smarthome.events.LiveEvents.PairingChanged;
import dev.homeserver.smarthome.events.LiveEvents.PermissionsChanged;
import dev.homeserver.smarthome.events.LiveEvents.RoomChanged;
import dev.homeserver.smarthome.events.LiveEvents.RoomDeleted;
import dev.homeserver.smarthome.events.LiveEvents.RoomsReordered;
import dev.homeserver.smarthome.events.LiveEvents.TokenRevoked;
import dev.homeserver.smarthome.events.LiveEvents.UserAccessEnded;
import dev.homeserver.smarthome.room.RoomController.RoomResponse;
import dev.homeserver.smarthome.room.RoomService;
import dev.homeserver.smarthome.room.RoomService.RoomWithCount;

/**
 * Turns application events into messages, after the transaction committed, and sends each one only to connections
 * whose user may see it: devices need {@code view} (admins see everything), rooms follow the room list rules,
 * confirmations go to the requester, the people who may decide them and admins.
 */
@Component
public class EventBroadcaster {

	private final EventHub hub;
	private final DeviceRepository devices;
	private final RoomService rooms;
	private final ConfirmationRepository confirmations;
	private final ConfirmationService confirmationService;
	private final ConfirmationViews confirmationViews;

	public EventBroadcaster(EventHub hub, DeviceRepository devices, RoomService rooms,
			ConfirmationRepository confirmations, ConfirmationService confirmationService,
			ConfirmationViews confirmationViews) {
		this.hub = hub;
		this.devices = devices;
		this.rooms = rooms;
		this.confirmations = confirmations;
		this.confirmationService = confirmationService;
		this.confirmationViews = confirmationViews;
	}

	public record DeviceState(long deviceId, Map<String, Object> state, Instant lastSeen) {
	}

	@TransactionalEventListener(fallbackExecution = true)
	public void on(DeviceStateUpdated event) {
		devices.findById(event.deviceId()).ifPresent(device -> {
			for (Connection c : hub.connections()) {
				if (c.access().canView(device)) {
					hub.send(c, "device.state", new DeviceState(device.getId(), device.getState(), device.getLastSeen()));
				}
			}
		});
	}

	/** Who no longer sees a device after the change (e.g. it moved to another room) gets {@code device.removed}. */
	@TransactionalEventListener(fallbackExecution = true)
	public void on(DeviceChanged event) {
		devices.findById(event.deviceId()).ifPresent(device -> {
			String type = event.before() == null ? "device.added" : "device.updated";
			for (Connection c : hub.connections()) {
				if (c.access().canView(device)) {
					hub.send(c, type, DeviceResponse.from(device, c.access()));
				}
				else if (event.before() != null && c.access().canView(event.before())) {
					hub.send(c, "device.removed", Map.of("deviceId", device.getId()));
				}
			}
		});
	}

	@TransactionalEventListener(fallbackExecution = true)
	public void on(DeviceRemoved event) {
		for (Connection c : hub.connections()) {
			if (c.access().canView(event.snapshot())) {
				hub.send(c, "device.removed", Map.of("deviceId", event.snapshot().getId()));
			}
		}
	}

	/** New rooms are empty, so only admins see them. */
	@TransactionalEventListener(fallbackExecution = true)
	public void on(RoomChanged event) {
		for (Connection c : hub.connections()) {
			if (event.created() && !c.isAdmin()) {
				continue;
			}
			RoomWithCount room;
			try {
				room = rooms.get(c.user(), event.roomId());
			}
			catch (ResponseStatusException e) {
				continue;
			}
			hub.send(c, event.created() ? "room.created" : "room.updated", RoomResponse.from(room));
		}
	}

	/** Only empty rooms can be deleted, so only admins saw it. */
	@TransactionalEventListener(fallbackExecution = true)
	public void on(RoomDeleted event) {
		for (Connection c : hub.connections()) {
			if (c.isAdmin()) {
				hub.send(c, "room.deleted", Map.of("roomId", event.roomId()));
			}
		}
	}

	/** Each client gets the new order of the rooms it sees. */
	@TransactionalEventListener(fallbackExecution = true)
	public void on(RoomsReordered event) {
		for (Connection c : hub.connections()) {
			var ids = rooms.list(c.user()).stream().map(r -> r.room().getId()).toList();
			hub.send(c, "rooms.reordered", Map.of("roomIds", ids));
		}
	}

	@TransactionalEventListener(fallbackExecution = true)
	public void on(ConfirmationChanged event) {
		confirmations.findById(event.confirmationId()).ifPresent(confirmation -> {
			String type = "confirmation." + (confirmation.getStatus() == ConfirmationStatus.PENDING ? "created"
					: confirmation.getStatus().dbValue());
			for (Connection c : hub.connections()) {
				if (confirmationService.isVisible(c.user(), confirmation)) {
					hub.send(c, type, confirmationViews.response(c.user(), confirmation));
				}
			}
		});
	}

	/** Reloads permissions of every connection; the affected users are told to reload their devices and rooms. */
	@TransactionalEventListener(fallbackExecution = true)
	public void on(PermissionsChanged event) {
		for (Connection c : hub.connections()) {
			if (!hub.refresh(c)) {
				continue;
			}
			boolean affected = (event.userId() == null && event.roleName() == null)
					|| (event.userId() != null && event.userId() == c.user().id())
					|| (event.roleName() != null && c.user().roles().contains(event.roleName()));
			if (affected) {
				hub.send(c, "permissions.changed", Map.of());
			}
		}
	}

	/** Pairing mode concerns admins only (only they can open it and add devices). */
	@TransactionalEventListener(fallbackExecution = true)
	public void on(PairingChanged event) {
		var data = new Pairing(event.adapter(), event.active(), event.endsAt());
		for (Connection c : hub.connections()) {
			if (c.isAdmin()) {
				hub.send(c, event.active() ? "pairing.started" : "pairing.stopped", data);
			}
		}
	}

	public record Pairing(String adapter, boolean active, Instant endsAt) {
	}

	/** A newly paired device, as in {@code GET /api/devices/discovered}; to admins, unless it was added already. */
	@TransactionalEventListener(fallbackExecution = true)
	public void on(DeviceDiscovered event) {
		var device = event.device();
		if (devices.existsByAdapterAndExternalId(device.adapter(), device.externalId())) {
			return;
		}
		for (Connection c : hub.connections()) {
			if (c.isAdmin()) {
				hub.send(c, "device.discovered", device);
			}
		}
	}

	@TransactionalEventListener(fallbackExecution = true)
	public void on(UserAccessEnded event) {
		hub.closeUser(event.userId());
	}

	@TransactionalEventListener(fallbackExecution = true)
	public void on(TokenRevoked event) {
		hub.closeToken(event.tokenId());
	}

}
