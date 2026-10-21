package dev.homeserver.smarthome.trusteddevice;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.homeserver.smarthome.audit.AuditResult;
import dev.homeserver.smarthome.audit.AuditService;
import dev.homeserver.smarthome.audit.Via;
import dev.homeserver.smarthome.error.ApiException;
import dev.homeserver.smarthome.user.CurrentUser;

/**
 * Phones that may sign approvals. Only people can register one, and only right after signing in (password, and the
 * second factor if set up): otherwise a stolen session could register its own key and approve its own unlock.
 */
@Service
public class TrustedDeviceService {

	private static final int MAX_NAME_LENGTH = 64;
	static final String AUDIT_ACTION = "trusted_device";

	private final TrustedDeviceRepository trustedDevices;
	private final TrustedDeviceProperties properties;
	private final AuditService audit;

	public TrustedDeviceService(TrustedDeviceRepository trustedDevices, TrustedDeviceProperties properties,
			AuditService audit) {
		this.trustedDevices = trustedDevices;
		this.properties = properties;
		this.audit = audit;
	}

	/**
	 * @param authTime when the caller last signed in (JWT claim {@code auth_time}), null for machine tokens
	 */
	@Transactional
	public TrustedDevice register(CurrentUser user, Via via, Instant authTime, String name, String publicKey) {
		if (user.identityId() == null) {
			throw ApiException.forbidden("phone.machine", "Nur Menschen können ein Handy registrieren.");
		}
		if (authTime == null || authTime.isBefore(Instant.now().minus(properties.maxAuthAge()))) {
			throw ApiException.forbidden("phone.fresh_login_required",
					"Bitte neu anmelden (Passwort und ggf. zweiter Faktor), um ein Handy zu registrieren.")
					.with("reason", "fresh_login_required");
		}
		String trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
			throw ApiException.badRequest("phone.name_invalid", "Der Name muss 1 bis " + MAX_NAME_LENGTH + " Zeichen haben.");
		}
		String key;
		try {
			key = DeviceKeys.normalize(publicKey == null ? "" : publicKey);
		}
		catch (IllegalArgumentException e) {
			throw ApiException.badRequest("phone.key_invalid", "Der Schlüssel ist ungültig: " + e.getMessage());
		}
		if (trustedDevices.existsByPublicKey(key)) {
			throw keyTaken();
		}
		TrustedDevice device;
		try {
			device = trustedDevices.saveAndFlush(new TrustedDevice(user.id(), trimmed, key));
		}
		catch (DataIntegrityViolationException e) {
			throw keyTaken();
		}
		audit.record(user, via, null, AUDIT_ACTION, Map.of("trustedDeviceId", device.getId(), "name", trimmed),
				AuditResult.REGISTERED, null);
		return device;
	}

	@Transactional(readOnly = true)
	public List<TrustedDevice> list(long userId) {
		return trustedDevices.findByUserIdOrderById(userId);
	}

	@Transactional(readOnly = true)
	public boolean hasActive(long userId) {
		return trustedDevices.existsByUserIdAndRevokedAtIsNull(userId);
	}

	/** Revokes one of the user's phones; {@code by} is the user or an admin. */
	@Transactional
	public TrustedDevice revoke(CurrentUser by, Via via, long userId, long id) {
		TrustedDevice device = trustedDevices.findById(id)
				.filter(d -> d.getUserId() == userId)
				.orElseThrow(() -> ApiException.notFound("phone.not_found", "Dieses Handy gibt es nicht."));
		if (!device.isRevoked()) {
			device.revoke();
			audit.record(by, via, null, AUDIT_ACTION,
					Map.of("trustedDeviceId", device.getId(), "name", device.getName(), "userId", userId),
					AuditResult.REVOKED, null);
		}
		return device;
	}

	/**
	 * Checks a signature made with one of the user's phones. 403 if the phone is unknown, someone else's, revoked, or
	 * the signature does not match.
	 */
	@Transactional
	public TrustedDevice verify(long userId, long trustedDeviceId, String payload, String signature) {
		TrustedDevice device = trustedDevices.findById(trustedDeviceId)
				.filter(d -> d.getUserId() == userId && !d.isRevoked())
				.orElseThrow(() -> ApiException.forbidden("phone.unknown", "Dieses Handy ist unbekannt oder gesperrt."));
		if (signature == null || !DeviceKeys.verify(device.getPublicKey(), payload, signature)) {
			throw ApiException.forbidden("phone.signature_invalid", "Die Unterschrift des Handys stimmt nicht.");
		}
		device.used();
		return device;
	}

	private static ApiException keyTaken() {
		return ApiException.conflict("phone.key_taken", "Dieser Schlüssel ist schon registriert.");
	}

}
