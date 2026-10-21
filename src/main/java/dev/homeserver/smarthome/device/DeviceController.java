package dev.homeserver.smarthome.device;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.homeserver.smarthome.audit.Via;
import dev.homeserver.smarthome.device.DeviceService.CommandStatus;
import dev.homeserver.smarthome.error.ApiException;
import dev.homeserver.smarthome.grant.Action;
import dev.homeserver.smarthome.grant.PermissionService;
import dev.homeserver.smarthome.grant.PermissionService.Access;
import dev.homeserver.smarthome.user.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * Devices. Everyone signed in sees the devices they may view, with what they may do with each one. Discovering, adding
 * and removing devices and locking actions is admin only.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

	private final DeviceService service;
	private final PermissionService permissions;

	public DeviceController(DeviceService service, PermissionService permissions) {
		this.service = service;
		this.permissions = permissions;
	}

	/**
	 * Devices the caller may view; with {@code roomId} only those in one room ({@code roomId=none}: devices without a
	 * room).
	 */
	@GetMapping
	public List<DeviceResponse> list(@AuthenticationPrincipal CurrentUser me,
			@RequestParam(required = false) String roomId) {
		List<Device> devices;
		if (roomId == null) {
			devices = service.list(me);
		}
		else if (roomId.equals("none")) {
			devices = service.listWithoutRoom(me);
		}
		else {
			try {
				devices = service.listInRoom(me, Long.parseLong(roomId));
			}
			catch (NumberFormatException e) {
				throw ApiException.badRequest("device.room_filter_invalid", "roomId muss eine Raum-ID oder „none“ sein.");
			}
		}
		Access access = permissions.accessOf(me);
		return devices.stream().map(d -> DeviceResponse.from(d, access)).toList();
	}

	/** Devices the adapters can see but that have not been added yet. */
	@GetMapping("/discovered")
	public List<DiscoveredDevice> discovered() {
		return service.discovered();
	}

	/** 404 also for devices the caller may not view. */
	@GetMapping("/{id}")
	public DeviceResponse get(@AuthenticationPrincipal CurrentUser me, @PathVariable long id) {
		return DeviceResponse.from(service.get(me, id), permissions.accessOf(me));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public DeviceResponse add(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody AddDeviceRequest request) {
		return DeviceResponse.from(service.add(request.adapter(), request.externalId(), request.name(), request.roomId()),
				permissions.accessOf(me));
	}

	/** Name and room need {@code configure}, locked actions are admin only. */
	@PatchMapping("/{id}")
	public DeviceResponse update(@AuthenticationPrincipal CurrentUser me, @PathVariable long id,
			@RequestBody UpdateDeviceRequest request) {
		return DeviceResponse.from(service.update(me, id, request.getName(), request.isRoomIdSet(), request.getRoomId(),
				request.getLockedActions()), permissions.accessOf(me));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable long id) {
		service.remove(id);
	}

	/**
	 * Sends a command, e.g. {@code {"state": "ON", "brightness": 120}}. The new state arrives asynchronously once the
	 * device reports it. 403 if the action is locked for the device or the caller lacks {@code control} (or
	 * {@code unlock}). If a confirmation is required, nothing is sent: {@code status: confirmation_required} with the
	 * id of the new confirmation. Clients name themselves in {@code X-Smarthome-Client} (web, app, widget) for the
	 * audit log.
	 */
	@PostMapping("/{id}/commands")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public CommandResponse command(@AuthenticationPrincipal CurrentUser me, @PathVariable long id,
			@RequestHeader(name = Via.HEADER, required = false) String client,
			@RequestBody @NotEmpty Map<String, Object> command) {
		var outcome = service.command(me, Via.of(me, client), id, command);
		var confirmation = outcome.confirmation();
		return new CommandResponse(outcome.status(), confirmation == null ? null : confirmation.getId(),
				confirmation == null ? null : confirmation.getExpiresAt());
	}

	/** With a confirmation: its id (see {@code /api/confirmations}) and until when someone has to decide. */
	public record CommandResponse(CommandStatus status, Long confirmationId, Instant expiresAt) {
	}

	/** What the caller may do with a device. */
	public record DevicePermission(Action action, boolean requiresConfirmation) {
	}

	public record DeviceResponse(
			Long id,
			String name,
			Long roomId,
			String type,
			String adapter,
			String externalId,
			Map<String, Object> capabilities,
			Map<String, Object> state,
			Instant lastSeen,
			List<String> lockedActions,
			List<DevicePermission> permissions) {

		public static DeviceResponse from(Device d, Access access) {
			var permissions = access.permissions(d).stream()
					.map(p -> new DevicePermission(p.action(), p.requiresConfirmation()))
					.toList();
			return new DeviceResponse(d.getId(), d.getName(), d.getRoomId(), d.getType(), d.getAdapter(),
					d.getExternalId(), d.getCapabilities(), d.getState(), d.getLastSeen(), d.getLockedActions(),
					permissions);
		}

	}

	public record AddDeviceRequest(@NotBlank String adapter, @NotBlank String externalId, @NotBlank String name,
			Long roomId) {
	}

	/**
	 * Fields left out stay unchanged; {@code "roomId": null} takes the device out of its room. A class instead of a
	 * record so that an explicit null can be told apart from a missing field.
	 */
	public static class UpdateDeviceRequest {

		private String name;
		private Long roomId;
		private boolean roomIdSet;
		private List<String> lockedActions;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public Long getRoomId() {
			return roomId;
		}

		public void setRoomId(Long roomId) {
			this.roomId = roomId;
			this.roomIdSet = true;
		}

		boolean isRoomIdSet() {
			return roomIdSet;
		}

		public List<String> getLockedActions() {
			return lockedActions;
		}

		public void setLockedActions(List<String> lockedActions) {
			this.lockedActions = lockedActions;
		}

	}

}
