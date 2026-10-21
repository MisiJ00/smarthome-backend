package dev.homeserver.smarthome.confirmation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.homeserver.smarthome.audit.Via;
import dev.homeserver.smarthome.error.ApiException;
import dev.homeserver.smarthome.confirmation.ConfirmationService.Challenge;
import dev.homeserver.smarthome.confirmation.ConfirmationService.SignedApproval;
import dev.homeserver.smarthome.grant.Action;
import dev.homeserver.smarthome.user.CurrentUser;

/**
 * Confirmations: everyone sees the requests they made and those they may decide (admins: all). Deciding needs a
 * person other than the requester who is an admin or may do the action on the device themselves; with a signature
 * from a registered phone (Face ID / fingerprint) people may also approve their own request.
 */
@RestController
@RequestMapping("/api/confirmations")
public class ConfirmationController {

	private final ConfirmationService service;
	private final ConfirmationViews views;

	public ConfirmationController(ConfirmationService service, ConfirmationViews views) {
		this.service = service;
		this.views = views;
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

	/**
	 * A one-time challenge for approving with a phone: the phone signs {@code payload} (SHA256withECDSA, after Face ID
	 * or a fingerprint) and sends the signature to {@code /approve} within {@code expiresAt}.
	 */
	@PostMapping("/{id}/challenge")
	public Challenge challenge(@AuthenticationPrincipal CurrentUser me, @PathVariable long id) {
		return service.challenge(me, id);
	}

	/**
	 * Sends the command. Without a body the usual rules apply (another person decides); with
	 * {@code {trustedDeviceId, nonce, signature}} from a registered phone people may also approve their own request.
	 * 409 if already decided or expired, 403 if the caller may not decide or the signature is invalid.
	 */
	@PostMapping("/{id}/approve")
	public ConfirmationResponse approve(@AuthenticationPrincipal CurrentUser me, @PathVariable long id,
			@RequestHeader(name = Via.HEADER, required = false) String client,
			@RequestBody(required = false) SignedApproval signed) {
		return response(me, service.approve(me, Via.of(me, client), id, signed));
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
	 * @param canApproveWithPhone the caller may approve it with a signature from a registered phone, their own
	 * request included (while it is pending)
	 */
	public record ConfirmationResponse(long id, long userId, String userName, long deviceId, String deviceName,
			Action action, Map<String, Object> params, ConfirmationStatus status, Via via, Instant createdAt,
			Instant expiresAt, Long decidedBy, String decidedByName, Instant decidedAt, boolean mine,
			boolean canDecide, boolean canApproveWithPhone) {
	}

	private ConfirmationResponse response(CurrentUser me, Confirmation c) {
		return views.response(me, c);
	}

	private static ConfirmationStatus parseStatus(String status) {
		if (status.equalsIgnoreCase("all")) {
			return null;
		}
		try {
			return ConfirmationStatus.valueOf(status.strip().toUpperCase());
		}
		catch (IllegalArgumentException e) {
			throw ApiException.badRequest("confirmation.status_invalid",
					"status muss pending, approved, rejected, expired oder all sein.");
		}
	}

}
