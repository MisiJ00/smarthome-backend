package dev.homeserver.smarthome.trusteddevice;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A one-time value a browser's authenticator signs when it creates a credential. Used up by the first attempt. */
@Entity
@Table(name = "webauthn_challenges")
public class WebAuthnChallenge {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	/** Base64url. */
	private String challenge;

	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	protected WebAuthnChallenge() {
	}

	WebAuthnChallenge(long userId, String challenge, Instant expiresAt) {
		this.userId = userId;
		this.challenge = challenge;
		this.createdAt = Instant.now();
		this.expiresAt = expiresAt;
	}

	void use() {
		usedAt = Instant.now();
	}

	public Long getUserId() {
		return userId;
	}

	public String getChallenge() {
		return challenge;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getUsedAt() {
		return usedAt;
	}

}
