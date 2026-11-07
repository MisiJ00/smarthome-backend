package dev.homeserver.smarthome.trusteddevice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
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
	private final WebAuthnProperties webauthn;
	private final WebAuthnChallengeStore webauthnChallenges;
	private final SecureRandom random = new SecureRandom();

	public TrustedDeviceService(TrustedDeviceRepository trustedDevices, TrustedDeviceProperties properties,
			AuditService audit, WebAuthnProperties webauthn, WebAuthnChallengeStore webauthnChallenges) {
		this.trustedDevices = trustedDevices;
		this.properties = properties;
		this.audit = audit;
		this.webauthn = webauthn;
		this.webauthnChallenges = webauthnChallenges;
	}

	/** What the browser needs to create a credential: the one-time challenge plus relying party and user entity. */
	public record WebAuthnOptions(String challenge, String rpId, String rpName, String userHandle, String userName,
			List<String> excludeCredentialIds, Instant expiresAt) {
	}

	/**
	 * @param authTime when the caller last signed in (JWT claim {@code auth_time}), null for machine tokens
	 */
	@Transactional
	public TrustedDevice register(CurrentUser user, Via via, Instant authTime, String name, String publicKey) {
		requireFreshHuman(user, authTime);
		String trimmed = cleanName(name);
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

	/**
	 * Step 1 of registering a browser: a one-time challenge and the entities for {@code navigator.credentials.create}.
	 * Same conditions as for a phone (a person, login at most 15 minutes old). The user handle is random per
	 * credential, so a new one never replaces an older passkey of the same account on the device.
	 */
	@Transactional
	public WebAuthnOptions webauthnOptions(CurrentUser user, Instant authTime) {
		requireFreshHuman(user, authTime);
		var challenge = webauthnChallenges.create(user.id());
		byte[] handle = new byte[16];
		random.nextBytes(handle);
		var exclude = trustedDevices.findByUserIdOrderById(user.id()).stream()
				.filter(d -> d.isWebauthn() && !d.isRevoked()).map(TrustedDevice::getCredentialId).toList();
		return new WebAuthnOptions(challenge.getChallenge(), webauthn.rpId(), webauthn.rpName(),
				WebAuthn.base64url(handle), user.name() + " (Freigabe)", exclude, challenge.getExpiresAt());
	}

	/**
	 * Step 2: registers the credential the browser made. Checks the challenge (once), origin, relying party, that the
	 * authenticator verified the user (fingerprint, face or PIN) and that it is an ES256 key.
	 *
	 * @param clientDataJson    Base64 or Base64url of clientDataJSON
	 * @param attestationObject Base64 or Base64url of the attestation object
	 */
	@Transactional
	public TrustedDevice registerWebAuthn(CurrentUser user, Via via, Instant authTime, String name, String clientDataJson,
			String attestationObject) {
		requireFreshHuman(user, authTime);
		String trimmed = cleanName(name);
		WebAuthn.Created created;
		try {
			byte[] clientData = WebAuthn.decode(clientDataJson);
			var data = WebAuthn.clientData(clientData);
			webauthnChallenges.consume(user.id(), data.challenge());
			WebAuthn.checkClientData(data, "webauthn.create", data.challenge(), webauthn.origins());
			created = WebAuthn.created(WebAuthn.decode(attestationObject));
		}
		catch (IllegalArgumentException e) {
			throw ApiException.badRequest("webauthn.invalid", "Der Passkey ist ungültig: " + e.getMessage());
		}
		if (!MessageDigest.isEqual(created.rpIdHash(),
				WebAuthn.sha256(webauthn.rpId().getBytes(StandardCharsets.UTF_8)))) {
			throw ApiException.badRequest("webauthn.invalid", "Der Passkey gehört zu einer anderen Adresse.");
		}
		if ((created.flags() & WebAuthn.FLAG_USER_VERIFIED) == 0 || (created.flags() & WebAuthn.FLAG_USER_PRESENT) == 0) {
			throw ApiException.badRequest("webauthn.user_verification_required",
					"Der Passkey muss mit Fingerabdruck, Gesicht oder Geräte-PIN gesichert sein.");
		}
		String credentialId = WebAuthn.base64url(created.credentialId());
		if (trustedDevices.existsByPublicKey(created.publicKeySpki()) || trustedDevices.existsByCredentialId(credentialId)) {
			throw keyTaken();
		}
		TrustedDevice device;
		try {
			device = trustedDevices.saveAndFlush(TrustedDevice.webauthn(user.id(), trimmed, created.publicKeySpki(),
					credentialId, created.signCount()));
		}
		catch (DataIntegrityViolationException e) {
			throw keyTaken();
		}
		audit.record(user, via, null, AUDIT_ACTION,
				Map.of("trustedDeviceId", device.getId(), "name", trimmed, "kind", TrustedDevice.KIND_WEBAUTHN),
				AuditResult.REGISTERED, null);
		return device;
	}

	private void requireFreshHuman(CurrentUser user, Instant authTime) {
		if (user.identityId() == null) {
			throw ApiException.forbidden("phone.machine", "Nur Menschen können ein Handy registrieren.");
		}
		if (authTime == null || authTime.isBefore(Instant.now().minus(properties.maxAuthAge()))) {
			throw ApiException.forbidden("phone.fresh_login_required",
					"Bitte neu anmelden (Passwort und ggf. zweiter Faktor), um ein Handy zu registrieren.")
					.with("reason", "fresh_login_required");
		}
	}

	private static String cleanName(String name) {
		String trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
			throw ApiException.badRequest("phone.name_invalid", "Der Name muss 1 bis " + MAX_NAME_LENGTH + " Zeichen haben.");
		}
		return trimmed;
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
	 * Checks a signature made with one of the user's phones or browser passkeys. 403 if the device is unknown,
	 * someone else's, revoked, or the signature does not match.
	 *
	 * <p>For a phone key the signature is over {@code payload}. For a browser passkey it is a WebAuthn assertion whose
	 * challenge is the SHA-256 of {@code payload}: {@code authenticatorData} and {@code clientDataJson} (Base64 or
	 * Base64url) come along, the signature covers {@code authenticatorData || SHA-256(clientDataJSON)}. Besides the
	 * signature the origin, relying party, user presence and verification (a gesture for every approval) and a rising
	 * signature counter (where the authenticator has one) are checked.
	 */
	@Transactional
	public TrustedDevice verify(long userId, long trustedDeviceId, String payload, String signature,
			String authenticatorData, String clientDataJson) {
		TrustedDevice device = trustedDevices.findById(trustedDeviceId)
				.filter(d -> d.getUserId() == userId && !d.isRevoked())
				.orElseThrow(() -> ApiException.forbidden("phone.unknown", "Dieses Handy ist unbekannt oder gesperrt."));
		boolean valid = device.isWebauthn()
				? verifyWebAuthn(device, payload, signature, authenticatorData, clientDataJson)
				: signature != null && DeviceKeys.verify(device.getPublicKey(), payload, signature);
		if (!valid) {
			throw ApiException.forbidden("phone.signature_invalid", "Die Unterschrift des Handys stimmt nicht.");
		}
		device.used();
		return device;
	}

	private boolean verifyWebAuthn(TrustedDevice device, String payload, String signature, String authenticatorData,
			String clientDataJson) {
		try {
			byte[] authData = WebAuthn.decode(authenticatorData);
			byte[] clientData = WebAuthn.decode(clientDataJson);
			byte[] der = WebAuthn.decode(signature);
			if (authData.length < 37) {
				return false;
			}
			var data = WebAuthn.clientData(clientData);
			String challenge = WebAuthn.base64url(WebAuthn.sha256(payload.getBytes(StandardCharsets.UTF_8)));
			WebAuthn.checkClientData(data, "webauthn.get", challenge, webauthn.origins());
			byte[] rpIdHash = Arrays.copyOfRange(authData, 0, 32);
			if (!MessageDigest.isEqual(rpIdHash,
					WebAuthn.sha256(webauthn.rpId().getBytes(StandardCharsets.UTF_8)))) {
				return false;
			}
			int flags = authData[32] & 0xff;
			if ((flags & WebAuthn.FLAG_USER_PRESENT) == 0 || (flags & WebAuthn.FLAG_USER_VERIFIED) == 0) {
				return false;
			}
			if (!WebAuthn.verifyAssertion(device.getPublicKey(), authData, clientData, der)) {
				return false;
			}
			long count = WebAuthn.signCount(authData);
			// A counter that does not rise hints at a cloned authenticator; many (Touch ID, Windows Hello) always send 0
			if ((count != 0 || device.getSignCount() != 0) && count <= device.getSignCount()) {
				return false;
			}
			device.signCount(count);
			return true;
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static ApiException keyTaken() {
		return ApiException.conflict("phone.key_taken", "Dieser Schlüssel ist schon registriert.");
	}

}
