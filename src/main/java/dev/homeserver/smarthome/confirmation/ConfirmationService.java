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
 * <p>Who may decide: a person (never the AI or a script), never the one who asked, and only an admin or someone who
 * may do the same action on the device themselves (with or without confirmation). Approving sends the command, after
 * checking locked actions and the requester's permission again.
 *
 * <p>Signed approvals: a person with a registered phone gets a one-time challenge, signs it on the phone (the key only
 * works with Face ID or a fingerprint) and sends the signature with the approval. Signed, people may also approve
 * their own requests. The AI has no phone and never approves.
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

	/** What the phone sends along with a signed approval. */
	public record SignedApproval(long trustedDeviceId, String nonce, String signature) {
	}

	/** A challenge and the exact text the phone has to sign. */
	public record Challenge(String nonce, String payload, Instant expiresAt) {
	}

	/**
	 * The text a phone signs (UTF-8, SHA256withECDSA): binds the signature to this confirmation, its action and
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
			throw ApiException.forbidden("confirmation.not_allowed", "Du darfst über diese Anfrage nicht entscheiden.");
		}
		if (!trustedDevices.hasActive(user.id())) {
			throw ApiException.conflict("phone.none", "Bitte zuerst dieses Handy für Freigaben registrieren.");
		}
		var challenge = challenges.create(confirmation.getId(), user.id());
		return new Challenge(challenge.getNonce(), payload(confirmation, challenge.getNonce()), challenge.getExpiresAt());
	}

	/**
	 * Whether the user may approve this confirmation with a signature from a phone: like {@link #canDecide}, but their
	 * own requests count too. Ignores the status and whether a phone is registered.
	 */
	@Transactional(readOnly = true)
	public boolean canApproveSigned(CurrentUser user, Confirmation confirmation) {
		if (user.identityId() == null) {
			return false;
		}
		return user.id() == confirmation.getUserId() || canDecide(user, confirmation);
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
				throw ApiException.forbidden("confirmation.not_allowed", "Du darfst über diese Anfrage nicht entscheiden.");
			}
			phone = trustedDevices.verify(user.id(), signed.trustedDeviceId(), payload(confirmation, signed.nonce()),
					signed.signature()).getName();
		}
		Device device = devices.findById(confirmation.getDeviceId()).orElseThrow(() -> notFound(id));
		// Things may have changed since the request
		CommandGuard.check(device, confirmation.getParams());
		var requester = users.findById(confirmation.getUserId()).map(u -> CurrentUser.of(u, null)).orElseThrow();
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

	@Transactional
	public Confirmation reject(CurrentUser user, Via via, long id) {
		var confirmation = pendingForDecision(user, id);
		Device device = devices.findById(confirmation.getDeviceId()).orElse(null);
		confirmation.decide(ConfirmationStatus.REJECTED, user.id());
		var requester = users.findById(confirmation.getUserId()).map(u -> CurrentUser.of(u, null)).orElse(null);
		audit.record(user, via, device, confirmation.getAction().dbValue(), withRequester(confirmation, requester),
				AuditResult.REJECTED, confirmation.getId());
		events.publishEvent(new ConfirmationChanged(confirmation.getId()));
		return confirmation;
	}

	/** Whether the user may approve or reject this confirmation (ignoring its status). */
	@Transactional(readOnly = true)
	public boolean canDecide(CurrentUser user, Confirmation confirmation) {
		if (user.identityId() == null || user.id() == confirmation.getUserId()) {
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

	private Confirmation pendingForDecision(CurrentUser user, long id) {
		var confirmation = pending(user, id);
		if (!canDecide(user, confirmation)) {
			throw user.id() == confirmation.getUserId()
					? ApiException.forbidden("confirmation.own_request",
							"Die eigene Anfrage kannst du nur mit einem registrierten Handy freigeben.")
					: ApiException.forbidden("confirmation.not_allowed", "Du darfst über diese Anfrage nicht entscheiden.");
		}
		return confirmation;
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
