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

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	private String name;

	/** Base64 X.509 SubjectPublicKeyInfo of an EC P-256 key. */
	@Column(name = "public_key")
	private String publicKey;

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

	void revoke() {
		if (revokedAt == null) {
			revokedAt = Instant.now();
		}
	}

	void used() {
		lastUsed = Instant.now();
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
