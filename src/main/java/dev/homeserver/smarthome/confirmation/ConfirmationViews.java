package dev.homeserver.smarthome.confirmation;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import dev.homeserver.smarthome.confirmation.ConfirmationController.ConfirmationResponse;
import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.trusteddevice.TrustedDeviceService;
import dev.homeserver.smarthome.user.CurrentUser;
import dev.homeserver.smarthome.user.User;
import dev.homeserver.smarthome.user.UserRepository;

/** A confirmation as one user sees it (names, and what they may do with it). Used by the API and live events. */
@Component
public class ConfirmationViews {

	private final ConfirmationService service;
	private final TrustedDeviceService trustedDevices;
	private final UserRepository users;
	private final DeviceRepository devices;

	public ConfirmationViews(ConfirmationService service, TrustedDeviceService trustedDevices, UserRepository users,
			DeviceRepository devices) {
		this.service = service;
		this.trustedDevices = trustedDevices;
		this.users = users;
		this.devices = devices;
	}

	@Transactional(readOnly = true)
	public ConfirmationResponse response(CurrentUser me, Confirmation c) {
		String userName = users.findById(c.getUserId()).map(User::getName).orElse(null);
		String deviceName = devices.findById(c.getDeviceId()).map(Device::getName).orElse(null);
		String decidedByName = c.getDecidedBy() == null ? null
				: users.findById(c.getDecidedBy()).map(User::getName).orElse(null);
		boolean pending = c.getStatus() == ConfirmationStatus.PENDING;
		boolean canDecide = pending && service.canDecide(me, c);
		boolean withPhone = pending && service.canApproveSigned(me, c) && trustedDevices.hasActive(me.id());
		return new ConfirmationResponse(c.getId(), c.getUserId(), userName, c.getDeviceId(), deviceName, c.getAction(),
				c.getParams(), c.getStatus(), c.getVia(), c.getCreatedAt(), c.getExpiresAt(), c.getDecidedBy(),
				decidedByName, c.getDecidedAt(), c.getUserId() == me.id(), canDecide, withPhone);
	}

}
