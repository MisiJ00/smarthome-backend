package dev.homeserver.smarthome.confirmation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A one-time value a phone signs to approve one confirmation. Used up by the first attempt, valid or not. */
@Entity
@Table(name = "confirmation_challenges")
public class ConfirmationChallenge {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "confirmation_id")
	private Long confirmationId;

	@Column(name = "user_id")
	private Long userId;

	private String nonce;

	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	protected ConfirmationChallenge() {
	}

	ConfirmationChallenge(long confirmationId, long userId, String nonce, Instant expiresAt) {
		this.confirmationId = confirmationId;
		this.userId = userId;
		this.nonce = nonce;
		this.createdAt = Instant.now();
		this.expiresAt = expiresAt;
	}

	void use() {
		usedAt = Instant.now();
	}

	public Long getConfirmationId() {
		return confirmationId;
	}

	public Long getUserId() {
		return userId;
	}

	public String getNonce() {
		return nonce;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getUsedAt() {
		return usedAt;
	}

}
