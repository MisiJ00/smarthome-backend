package dev.homeserver.smarthome.device;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

	private final DeviceService service;

	public DeviceController(DeviceService service) {
		this.service = service;
	}

	/** All devices, or with {@code roomId} only those in one room ({@code roomId=none}: devices without a room). */
	@GetMapping
	public List<DeviceResponse> list(@RequestParam(required = false) String roomId) {
		List<Device> devices;
		if (roomId == null) {
			devices = service.list();
		}
		else if (roomId.equals("none")) {
			devices = service.listWithoutRoom();
		}
		else {
			try {
				devices = service.listInRoom(Long.parseLong(roomId));
			}
			catch (NumberFormatException e) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomId must be a room id or \"none\"");
			}
		}
		return devices.stream().map(DeviceResponse::from).toList();
	}

	/** Devices the adapters can see but that have not been added yet. */
	@GetMapping("/discovered")
	public List<DiscoveredDevice> discovered() {
		return service.discovered();
	}

	@GetMapping("/{id}")
	public DeviceResponse get(@PathVariable long id) {
		return DeviceResponse.from(service.get(id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public DeviceResponse add(@Valid @RequestBody AddDeviceRequest request) {
		return DeviceResponse.from(service.add(request.adapter(), request.externalId(), request.name(), request.roomId()));
	}

	@PatchMapping("/{id}")
	public DeviceResponse update(@PathVariable long id, @RequestBody UpdateDeviceRequest request) {
		return DeviceResponse.from(service.update(id, request.getName(), request.isRoomIdSet(), request.getRoomId(),
				request.getLockedActions()));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable long id) {
		service.remove(id);
	}

	/**
	 * Sends a command, e.g. {@code {"state": "ON", "brightness": 120}}. The new state arrives
	 * asynchronously once the device reports it.
	 */
	@PostMapping("/{id}/commands")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void command(@PathVariable long id, @RequestBody @NotEmpty Map<String, Object> command) {
		service.command(id, command);
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
			List<String> lockedActions) {

		static DeviceResponse from(Device d) {
			return new DeviceResponse(d.getId(), d.getName(), d.getRoomId(), d.getType(), d.getAdapter(),
					d.getExternalId(), d.getCapabilities(), d.getState(), d.getLastSeen(), d.getLockedActions());
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
