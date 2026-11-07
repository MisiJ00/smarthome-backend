package dev.homeserver.smarthome.trusteddevice;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A person's phone whose key may sign approvals. The private key never leaves the phone and can only be used with
 * Face ID or a fingerprint; the backend only knows the public key.
 */
@Entity
@Table(name = "trusted_devices")
public class TrustedDevice {

	public static final String KIND_KEY = "key";
	public static final String KIND_WEBAUTHN = "webauthn";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	private String name;

	/** Base64 X.509 SubjectPublicKeyInfo of an EC P-256 key. */
	@Column(name = "public_key")
	private String publicKey;

	/** {@code key}: a phone's key in the Secure Enclave / Keystore; {@code webauthn}: a browser's passkey. */
	private String kind = KIND_KEY;

	/** Base64url credential id, only for {@code webauthn}. */
	@Column(name = "credential_id")
	private String credentialId;

	@Column(name = "sign_count")
	private long signCount;

	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@Column(name = "last_used")
	private Instant lastUsed;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	protected TrustedDevice() {
	}

	TrustedDevice(long userId, String name, String publicKey) {
		this.userId = userId;
		this.name = name;
		this.publicKey = publicKey;
		this.createdAt = Instant.now();
	}

	static TrustedDevice webauthn(long userId, String name, String publicKey, String credentialId, long signCount) {
		var device = new TrustedDevice(userId, name, publicKey);
		device.kind = KIND_WEBAUTHN;
		device.credentialId = credentialId;
		device.signCount = signCount;
		return device;
	}

	void signCount(long value) {
		signCount = value;
	}

	void revoke() {
		if (revokedAt == null) {
			revokedAt = Instant.now();
		}
	}

	void used() {
		lastUsed = Instant.now();
	}

	public boolean isWebauthn() {
		return KIND_WEBAUTHN.equals(kind);
	}

	public String getKind() {
		return kind;
	}

	public String getCredentialId() {
		return credentialId;
	}

	public long getSignCount() {
		return signCount;
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getName() {
		return name;
	}

	public String getPublicKey() {
		return publicKey;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLastUsed() {
		return lastUsed;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

}
