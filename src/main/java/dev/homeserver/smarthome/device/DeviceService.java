package dev.homeserver.smarthome.device;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonProperty;

import dev.homeserver.smarthome.audit.AuditResult;
import dev.homeserver.smarthome.audit.AuditService;
import dev.homeserver.smarthome.audit.Via;
import dev.homeserver.smarthome.confirmation.Confirmation;
import dev.homeserver.smarthome.confirmation.ConfirmationService;
import dev.homeserver.smarthome.error.ApiException;
import dev.homeserver.smarthome.events.LiveEvents.DeviceChanged;
import dev.homeserver.smarthome.events.LiveEvents.DeviceRemoved;
import dev.homeserver.smarthome.events.LiveEvents.DeviceStateUpdated;
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
	private final ConfirmationService confirmations;
	private final AuditService audit;
	private final DeviceCommandSender sender;
	private final ApplicationEventPublisher events;

	public DeviceService(DeviceRepository devices, RoomRepository rooms, PermissionService permissions,
			ConfirmationService confirmations, AuditService audit, DeviceCommandSender sender,
			ApplicationEventPublisher events) {
		this.devices = devices;
		this.rooms = rooms;
		this.permissions = permissions;
		this.confirmations = confirmations;
		this.audit = audit;
		this.sender = sender;
		this.events = events;
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
			throw ApiException.notFound("room.not_found", "Diesen Raum gibt es nicht.");
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
		return sender.all().stream()
				.flatMap(adapter -> adapter.discovered().stream())
				.filter(d -> !devices.existsByAdapterAndExternalId(d.adapter(), d.externalId()))
				.toList();
	}

	@Transactional
	public Device add(String adapter, String externalId, String name, Long roomId) {
		if (devices.existsByAdapterAndExternalId(adapter, externalId)) {
			throw ApiException.conflict("device.already_added", "Dieses Gerät wurde schon übernommen.");
		}
		if (roomId != null && !rooms.existsById(roomId)) {
			throw ApiException.badRequest("room.not_found", "Diesen Raum gibt es nicht.");
		}
		var discovered = sender.adapter(adapter).discovered().stream()
				.filter(d -> d.externalId().equals(externalId))
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("device.not_discovered",
						"Das Gerät ist nicht (mehr) unter den gefundenen Geräten."));
		var device = save(new Device(discovered, name, roomId));
		events.publishEvent(new DeviceChanged(device.getId(), null));
		return device;
	}

	/**
	 * Null fields stay unchanged. With {@code setRoom} the device moves to {@code roomId}, null meaning no room. Name and
	 * room need {@code configure}, locked actions can only be changed by admins.
	 */
	@Transactional
	public Device update(CurrentUser user, long id, String name, boolean setRoom, Long roomId,
			List<String> lockedActions) {
		var device = get(user, id);
		var before = device.copy();
		var access = permissions.accessOf(user);
		if ((name != null || setRoom) && access.can(device, Action.CONFIGURE) != Decision.ALLOWED) {
			throw ApiException.forbidden("device.configure_not_allowed",
					"Du darfst " + device.getName() + " nicht umbenennen oder verschieben.");
		}
		if (lockedActions != null && !access.isAdmin()) {
			throw ApiException.forbidden("device.lock_actions_admin_only", "Nur Admins können Aktionen sperren.");
		}
		if (name != null) {
			device.setName(name);
		}
		if (setRoom) {
			if (roomId != null && !rooms.existsById(roomId)) {
				throw ApiException.badRequest("room.not_found", "Diesen Raum gibt es nicht.");
			}
			device.setRoomId(roomId);
		}
		if (lockedActions != null) {
			device.setLockedActions(lockedActions);
		}
		var saved = save(device);
		events.publishEvent(new DeviceChanged(saved.getId(), before));
		return saved;
	}

	@Transactional
	public void remove(long id) {
		var device = get(id);
		var snapshot = device.copy();
		devices.delete(device);
		events.publishEvent(new DeviceRemoved(snapshot));
	}

	/** What a command led to; the confirmation is set when one was created. */
	public record CommandOutcome(CommandStatus status, Confirmation confirmation) {
	}

	/**
	 * Locked actions are refused for everyone (403). Then {@code can()}: unlocking needs {@code unlock}, everything
	 * else {@code control}. If a confirmation is required, nothing is sent; a pending confirmation is created instead.
	 * Every outcome ends up in the audit log. No transaction here, so refusals are logged before the exception.
	 */
	public CommandOutcome command(CurrentUser user, Via via, long id, Map<String, Object> command) {
		var device = get(id);
		Action action = CommandGuard.permission(command);
		Access access = permissions.accessOf(user);
		if (!access.canView(device)) {
			audit.record(user, via, device, action.dbValue(), command, AuditResult.DENIED, null);
			throw notFound(id);
		}
		try {
			CommandGuard.check(device, command);
		}
		catch (CommandRefusedException e) {
			audit.record(user, via, device, action.dbValue(), command, AuditResult.LOCKED, null);
			throw e;
		}
		switch (access.can(device, action)) {
			case DENIED -> {
				audit.record(user, via, device, action.dbValue(), command, AuditResult.DENIED, null);
				throw new CommandRefusedException("device.not_allowed", action == Action.UNLOCK
						? "Du darfst " + device.getName() + " nicht aufschließen."
						: "Du darfst " + device.getName() + " nicht schalten.");
			}
			case NEEDS_CONFIRMATION -> {
				var confirmation = confirmations.request(user, via, device, action, command);
				return new CommandOutcome(CommandStatus.CONFIRMATION_REQUIRED, confirmation);
			}
			case ALLOWED -> {
			}
		}
		sender.send(device, command);
		audit.record(user, via, device, action.dbValue(), command, AuditResult.SENT, null);
		return new CommandOutcome(CommandStatus.SENT, null);
	}

	@EventListener
	@Transactional
	public void onStateChanged(DeviceStateChanged event) {
		devices.findByAdapterAndExternalId(event.adapter(), event.externalId()).ifPresent(device -> {
			device.mergeState(event.state(), Instant.now());
			events.publishEvent(new DeviceStateUpdated(device.getId()));
		});
	}

	private List<Device> visible(CurrentUser user, List<Device> candidates) {
		Access access = permissions.accessOf(user);
		return candidates.stream().filter(access::canView).toList();
	}

	private static ApiException notFound(long id) {
		return ApiException.notFound("device.not_found", "Dieses Gerät gibt es nicht.");
	}

	private Device save(Device device) {
		try {
			return devices.saveAndFlush(device);
		}
		catch (DataIntegrityViolationException e) {
			throw ApiException.badRequest("room.not_found", "Diesen Raum gibt es nicht.");
		}
	}

}
