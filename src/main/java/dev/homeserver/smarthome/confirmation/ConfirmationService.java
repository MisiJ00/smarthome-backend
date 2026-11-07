package dev.homeserver.smarthome.confirmation;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.homeserver.smarthome.audit.AuditResult;
import dev.homeserver.smarthome.audit.AuditService;
import dev.homeserver.smarthome.audit.Via;
import dev.homeserver.smarthome.device.CommandGuard;
import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceCommandSender;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.error.ApiException;
import dev.homeserver.smarthome.events.LiveEvents.ConfirmationChanged;
import dev.homeserver.smarthome.grant.Action;
import dev.homeserver.smarthome.grant.PermissionService;
import dev.homeserver.smarthome.grant.PermissionService.Decision;
import dev.homeserver.smarthome.trusteddevice.TrustedDeviceService;
import dev.homeserver.smarthome.user.CurrentUser;
import dev.homeserver.smarthome.user.Role;
import dev.homeserver.smarthome.user.UserRepository;

/**
 * Commands that need a confirmation (always unlocking, otherwise grants with {@code requires_confirmation}) wait here
 * until someone approves or rejects them, or they expire.
 *
 * <p>A person's request only that person approves, signed with their phone: they get a one-time challenge, sign it
 * on the phone (the key only works with Face ID or a fingerprint) and send the signature with the approval. Nobody
 * else, not even an admin, approves or rejects it; the person may withdraw it (stored as rejected by themselves).
 *
 * <p>Requests of the AI or a script (machine users, no person behind them) are decided by people: an admin or someone
 * who may do the same action on the device themselves (with or without confirmation), with or without a phone.
 * Machines never decide.
 *
 * <p>Approving sends the command, after checking locked actions and the requester's permission again.
 */
@Service
public class ConfirmationService {

	private final ConfirmationRepository confirmations;
	private final DeviceRepository devices;
	private final UserRepository users;
	private final PermissionService permissions;
	private final DeviceCommandSender sender;
	private final AuditService audit;
	private final ConfirmationProperties properties;
	private final ChallengeStore challenges;
	private final TrustedDeviceService trustedDevices;
	private final ApplicationEventPublisher events;

	public ConfirmationService(ConfirmationRepository confirmations, DeviceRepository devices, UserRepository users,
			PermissionService permissions, DeviceCommandSender sender, AuditService audit,
			ConfirmationProperties properties, ChallengeStore challenges, TrustedDeviceService trustedDevices,
			ApplicationEventPublisher events) {
		this.confirmations = confirmations;
		this.devices = devices;
		this.users = users;
		this.permissions = permissions;
		this.sender = sender;
		this.audit = audit;
		this.properties = properties;
		this.challenges = challenges;
		this.trustedDevices = trustedDevices;
		this.events = events;
	}

	/**
	 * What the phone or browser sends along with a signed approval. {@code authenticatorData} and {@code clientDataJson}
	 * (Base64 or Base64url) only for a browser passkey, where the signature is a WebAuthn assertion over the
	 * SHA-256 of the {@link #payload}.
	 */
	public record SignedApproval(long trustedDeviceId, String nonce, String signature, String authenticatorData,
			String clientDataJson) {
	}

	/** A challenge and the exact text the phone has to sign. */
	public record Challenge(String nonce, String payload, Instant expiresAt) {
	}

	/**
	 * The text a phone signs (UTF-8, SHA256withECDSA; a browser passkey signs its SHA-256 as WebAuthn challenge): binds the signature to this confirmation, its action and
	 * device, and one nonce.
	 */
	public static String payload(Confirmation confirmation, String nonce) {
		return "smarthome-approval:v1\n" + confirmation.getId() + "\n" + confirmation.getAction().dbValue() + "\n"
				+ confirmation.getDeviceId() + "\n" + nonce;
	}

	/** A one-time challenge for approving with a phone. 409 if the caller has no active phone. */
	@Transactional
	public Challenge challenge(CurrentUser user, long id) {
		var confirmation = get(user, id);
		if (confirmation.getStatus() != ConfirmationStatus.PENDING) {
			throw ApiException.conflict("confirmation.decided", "Diese Anfrage ist schon " + statusText(confirmation) + ".")
					.with("status", confirmation.getStatus().dbValue());
		}
		if (!canApproveSigned(user, confirmation)) {
			requireDecide(user, confirmation);
		}
		if (!trustedDevices.hasActive(user.id())) {
			throw ApiException.conflict("phone.none", "Bitte zuerst dieses Handy für Freigaben registrieren.");
		}
		var challenge = challenges.create(confirmation.getId(), user.id());
		return new Challenge(challenge.getNonce(), payload(confirmation, challenge.getNonce()), challenge.getExpiresAt());
	}

	/**
	 * Whether the user may approve this confirmation with a signature from a phone: a person their own request, and
	 * requests of machines whoever may {@link #canDecide decide} them. Ignores the status and whether a phone is
	 * registered.
	 */
	@Transactional(readOnly = true)
	public boolean canApproveSigned(CurrentUser user, Confirmation confirmation) {
		if (user.identityId() == null) {
			return false;
		}
		return user.id() == confirmation.getUserId() ? requestedByPerson(confirmation) : canDecide(user, confirmation);
	}

	/** Whether the user may withdraw this confirmation: their own request (ignoring its status). */
	public boolean canWithdraw(CurrentUser user, Confirmation confirmation) {
		return user.identityId() != null && user.id() == confirmation.getUserId();
	}

	/** Creates a pending confirmation for a command that was not sent. */
	@Transactional
	public Confirmation request(CurrentUser user, Via via, Device device, Action action, Map<String, Object> command) {
		var confirmation = confirmations.save(new Confirmation(user.id(), device.getId(), action, command, via,
				Instant.now().plus(properties.lifetime())));
		audit.record(user, via, device, action.dbValue(), command, AuditResult.PENDING, confirmation.getId());
		events.publishEvent(new ConfirmationChanged(confirmation.getId()));
		return confirmation;
	}

	/**
	 * Confirmations the user asked for or may decide, newest first. Admins see all. {@code status} null means all
	 * statuses.
	 */
	@Transactional
	public List<Confirmation> list(CurrentUser user, ConfirmationStatus status) {
		expireDue();
		return confirmations.findAllByOrderByIdDesc().stream()
				.filter(c -> status == null || c.getStatus() == status)
				.filter(c -> isVisible(user, c))
				.toList();
	}

	@Transactional
	public Confirmation get(CurrentUser user, long id) {
		expireDue();
		var confirmation = confirmations.findById(id).orElseThrow(() -> notFound(id));
		if (!isVisible(user, confirmation)) {
			throw notFound(id);
		}
		return confirmation;
	}

	/** Approves and sends the command. 409 if it is no longer pending, 403 if the user may not decide. */
	@Transactional
	public Confirmation approve(CurrentUser user, Via via, long id) {
		return approve(user, via, id, null);
	}

	/**
	 * Approves and sends the command; with {@code signed} also the user's own request. The challenge is used up first
	 * (in its own transaction), so a nonce works at most once, even when the signature is wrong.
	 */
	@Transactional
	public Confirmation approve(CurrentUser user, Via via, long id, SignedApproval signed) {
		String phone = null;
		Confirmation confirmation;
		if (signed == null) {
			confirmation = pendingForDecision(user, id);
		}
		else {
			if (user.identityId() == null) {
				throw ApiException.forbidden("phone.machine", "Nur Menschen können mit einem Handy freigeben.");
			}
			challenges.consume(id, user.id(), signed.nonce());
			confirmation = pending(user, id);
			if (!canApproveSigned(user, confirmation)) {
				requireDecide(user, confirmation);
			}
			phone = trustedDevices.verify(user.id(), signed.trustedDeviceId(), payload(confirmation, signed.nonce()),
					signed.signature(), signed.authenticatorData(), signed.clientDataJson()).getName();
		}
		Device device = devices.findById(confirmation.getDeviceId()).orElseThrow(() -> notFound(id));
		// Things may have changed since the request
		CommandGuard.check(device, confirmation.getParams());
		// The requester may be gone or deactivated (e.g. a machine whose token was revoked) since the request
		var requesterUser = users.findById(confirmation.getUserId()).orElseThrow(() -> ApiException
				.conflict("confirmation.requester_gone", "Wer gefragt hat, gibt es nicht mehr."));
		if (!requesterUser.isActive()) {
			throw ApiException.conflict("confirmation.requester_inactive", "Wer gefragt hat, ist deaktiviert.");
		}
		var requester = CurrentUser.of(requesterUser, null);
		if (permissions.can(requester, device, confirmation.getAction()) == Decision.DENIED) {
			throw ApiException.conflict("confirmation.requester_not_allowed",
					"Wer gefragt hat, darf das inzwischen nicht mehr.");
		}
		sender.send(device, confirmation.getParams());
		confirmation.decide(ConfirmationStatus.APPROVED, user.id());
		var params = withRequester(confirmation, requester);
		if (phone != null) {
			params.put("approvedWith", phone);
		}
		audit.record(user, via, device, confirmation.getAction().dbValue(), params, AuditResult.APPROVED,
				confirmation.getId());
		events.publishEvent(new ConfirmationChanged(confirmation.getId()));
		return confirmation;
	}

	/**
	 * Rejects a machine's request (whoever may decide it) or withdraws an own request (the person who asked; stored
	 * as rejected by them, e.g. when they cancel the fingerprint). 409 if no longer pending, 403 otherwise.
	 */
	@Transactional
	public Confirmation reject(CurrentUser user, Via via, long id) {
		var confirmation = pending(user, id);
		if (!canWithdraw(user, confirmation)) {
			requireDecide(user, confirmation);
		}
		Device device = devices.findById(confirmation.getDeviceId()).orElse(null);
		confirmation.decide(ConfirmationStatus.REJECTED, user.id());
		var requester = users.findById(confirmation.getUserId()).map(u -> CurrentUser.of(u, null)).orElse(null);
		var params = withRequester(confirmation, requester);
		if (canWithdraw(user, confirmation)) {
			// the requester themselves: withdrawn, not refused by someone
			params.put("withdrawn", true);
		}
		audit.record(user, via, device, confirmation.getAction().dbValue(), params, AuditResult.REJECTED,
				confirmation.getId());
		events.publishEvent(new ConfirmationChanged(confirmation.getId()));
		return confirmation;
	}

	/**
	 * Whether the user may approve or reject this confirmation without a phone (ignoring its status): only requests of
	 * machines, and only people other than the requester who are admins or may do the action themselves.
	 */
	@Transactional(readOnly = true)
	public boolean canDecide(CurrentUser user, Confirmation confirmation) {
		if (user.identityId() == null || user.id() == confirmation.getUserId() || requestedByPerson(confirmation)) {
			return false;
		}
		if (user.roles().contains(Role.ADMIN)) {
			return true;
		}
		return devices.findById(confirmation.getDeviceId())
				.map(device -> permissions.can(user, device, confirmation.getAction()) != Decision.DENIED)
				.orElse(false);
	}

	/** Marks confirmations nobody decided in time as expired. Also runs before every read. */
	@Scheduled(fixedDelayString = "${smarthome.confirmations.expiry-check:30s}")
	@Transactional
	public void expireDue() {
		for (Confirmation confirmation : confirmations.findDue(Instant.now())) {
			confirmation.decide(ConfirmationStatus.EXPIRED, null);
			var requester = users.findById(confirmation.getUserId()).map(u -> CurrentUser.of(u, null)).orElse(null);
			audit.record(requester, Via.SYSTEM, devices.findById(confirmation.getDeviceId()).orElse(null),
					confirmation.getAction().dbValue(), confirmation.getParams(), AuditResult.EXPIRED,
					confirmation.getId());
			events.publishEvent(new ConfirmationChanged(confirmation.getId()));
		}
	}

	/** Whether a person (not the AI or a script) asked; then only they approve it, with their phone. */
	@Transactional(readOnly = true)
	public boolean requestedByPerson(Confirmation confirmation) {
		return users.findById(confirmation.getUserId()).map(u -> !u.isMachine()).orElse(false);
	}

	private Confirmation pendingForDecision(CurrentUser user, long id) {
		var confirmation = pending(user, id);
		requireDecide(user, confirmation);
		return confirmation;
	}

	private void requireDecide(CurrentUser user, Confirmation confirmation) {
		if (canDecide(user, confirmation)) {
			return;
		}
		if (user.id() == confirmation.getUserId()) {
			throw ApiException.forbidden("confirmation.own_request",
					"Die eigene Anfrage kannst du nur mit deinem registrierten Handy freigeben.");
		}
		if (requestedByPerson(confirmation)) {
			throw ApiException.forbidden("confirmation.person_request",
					"Diese Anfrage kann nur die Person freigeben, die sie gestellt hat, mit ihrem Handy.");
		}
		throw ApiException.forbidden("confirmation.not_allowed", "Du darfst über diese Anfrage nicht entscheiden.");
	}

	/** Locked for the rest of the transaction; 404 if not visible, 409 if no longer pending. */
	private Confirmation pending(CurrentUser user, long id) {
		var confirmation = confirmations.findForUpdate(id).orElseThrow(() -> notFound(id));
		if (!isVisible(user, confirmation)) {
			throw notFound(id);
		}
		if (confirmation.isDue(Instant.now())) {
			expireDue();
		}
		if (confirmation.getStatus() != ConfirmationStatus.PENDING) {
			throw ApiException.conflict("confirmation.decided", "Diese Anfrage ist schon " + statusText(confirmation) + ".")
					.with("status", confirmation.getStatus().dbValue());
		}
		return confirmation;
	}

	/** The requester, people who may decide, and admins see a confirmation. */
	@Transactional(readOnly = true)
	public boolean isVisible(CurrentUser user, Confirmation confirmation) {
		return user.roles().contains(Role.ADMIN) || user.id() == confirmation.getUserId()
				|| canDecide(user, confirmation);
	}

	private static Map<String, Object> withRequester(Confirmation confirmation, CurrentUser requester) {
		var params = new HashMap<>(confirmation.getParams());
		params.put("requestedBy", requester == null ? confirmation.getUserId() : requester.name());
		return params;
	}

	private static ApiException notFound(long id) {
		return ApiException.notFound("confirmation.not_found", "Diese Anfrage gibt es nicht (mehr).");
	}

	private static String statusText(Confirmation confirmation) {
		return switch (confirmation.getStatus()) {
			case APPROVED -> "freigegeben";
			case REJECTED -> "abgelehnt";
			case EXPIRED -> "abgelaufen";
			case PENDING -> "offen";
		};
	}

}
