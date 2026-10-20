package dev.homeserver.smarthome.confirmation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.homeserver.smarthome.audit.Via;
import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.grant.Action;
import dev.homeserver.smarthome.user.CurrentUser;
import dev.homeserver.smarthome.user.User;
import dev.homeserver.smarthome.user.UserRepository;

/**
 * Confirmations: everyone sees the requests they made and those they may decide (admins: all). Deciding needs a
 * person other than the requester who is an admin or may do the action on the device themselves.
 */
@RestController
@RequestMapping("/api/confirmations")
public class ConfirmationController {

	private final ConfirmationService service;
	private final UserRepository users;
	private final DeviceRepository devices;

	public ConfirmationController(ConfirmationService service, UserRepository users, DeviceRepository devices) {
		this.service = service;
		this.users = users;
		this.devices = devices;
	}

	/** {@code status}: pending (default), approved, rejected, expired or all. Newest first. */
	@GetMapping
	public List<ConfirmationResponse> list(@AuthenticationPrincipal CurrentUser me,
			@RequestParam(defaultValue = "pending") String status) {
		ConfirmationStatus filter = parseStatus(status);
		return service.list(me, filter).stream().map(c -> response(me, c)).toList();
	}

	@GetMapping("/{id}")
	public ConfirmationResponse get(@AuthenticationPrincipal CurrentUser me, @PathVariable long id) {
		return response(me, service.get(me, id));
	}

	/** Sends the command. 409 if already decided or expired, 403 if the caller may not decide. */
	@PostMapping("/{id}/approve")
	public ConfirmationResponse approve(@AuthenticationPrincipal CurrentUser me, @PathVariable long id,
			@RequestHeader(name = Via.HEADER, required = false) String client) {
		return response(me, service.approve(me, Via.of(me, client), id));
	}

	@PostMapping("/{id}/reject")
	public ConfirmationResponse reject(@AuthenticationPrincipal CurrentUser me, @PathVariable long id,
			@RequestHeader(name = Via.HEADER, required = false) String client) {
		return response(me, service.reject(me, Via.of(me, client), id));
	}

	/**
	 * @param params    the command that is sent once approved
	 * @param mine      the caller asked for it
	 * @param canDecide the caller may approve or reject it (while it is pending)
	 */
	public record ConfirmationResponse(long id, long userId, String userName, long deviceId, String deviceName,
			Action action, Map<String, Object> params, ConfirmationStatus status, Via via, Instant createdAt,
			Instant expiresAt, Long decidedBy, String decidedByName, Instant decidedAt, boolean mine,
			boolean canDecide) {
	}

	private ConfirmationResponse response(CurrentUser me, Confirmation c) {
		String userName = users.findById(c.getUserId()).map(User::getName).orElse(null);
		String deviceName = devices.findById(c.getDeviceId()).map(Device::getName).orElse(null);
		String decidedByName = c.getDecidedBy() == null ? null
				: users.findById(c.getDecidedBy()).map(User::getName).orElse(null);
		boolean canDecide = c.getStatus() == ConfirmationStatus.PENDING && service.canDecide(me, c);
		return new ConfirmationResponse(c.getId(), c.getUserId(), userName, c.getDeviceId(), deviceName, c.getAction(),
				c.getParams(), c.getStatus(), c.getVia(), c.getCreatedAt(), c.getExpiresAt(), c.getDecidedBy(),
				decidedByName, c.getDecidedAt(), c.getUserId() == me.id(), canDecide);
	}

	private static ConfirmationStatus parseStatus(String status) {
		if (status.equalsIgnoreCase("all")) {
			return null;
		}
		try {
			return ConfirmationStatus.valueOf(status.strip().toUpperCase());
		}
		catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"status must be pending, approved, rejected, expired or all");
		}
	}

}
