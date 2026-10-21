package dev.homeserver.smarthome.z2m;

import java.time.Instant;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.homeserver.smarthome.audit.Via;
import dev.homeserver.smarthome.user.CurrentUser;
import dev.homeserver.smarthome.z2m.Z2mAdapter.PermitJoin;

/** Pairing mode for Zigbee devices (admin only). */
@RestController
@RequestMapping("/api/adapters/z2m/permit-join")
public class PairingController {

	private final PairingService service;

	public PairingController(PairingService service) {
		this.service = service;
	}

	/** {@code known} is false until Zigbee2MQTT has reported its state. */
	@GetMapping
	public PairingResponse status() {
		return PairingResponse.from(service.status());
	}

	/** Opens pairing, by default for the maximum of 254 seconds. */
	@PostMapping
	public PairingResponse start(@AuthenticationPrincipal CurrentUser me,
			@RequestHeader(name = Via.HEADER, required = false) String client,
			@RequestBody(required = false) StartRequest request) {
		int seconds = request == null || request.seconds() == null ? PairingService.MAX_SECONDS : request.seconds();
		return PairingResponse.from(service.start(me, Via.of(me, client), seconds));
	}

	@DeleteMapping
	public PairingResponse stop(@AuthenticationPrincipal CurrentUser me,
			@RequestHeader(name = Via.HEADER, required = false) String client) {
		return PairingResponse.from(service.stop(me, Via.of(me, client)));
	}

	public record StartRequest(Integer seconds) {
	}

	public record PairingResponse(String adapter, boolean active, Instant endsAt, boolean known) {

		static PairingResponse from(PermitJoin state) {
			return new PairingResponse(Z2mAdapter.NAME, state.active(), state.endsAt(), state.known());
		}

	}

}
