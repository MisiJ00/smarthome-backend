package dev.homeserver.smarthome.token;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A machine token. Only its SHA-256 hash is stored; the token itself is shown once when it is created. */
@Entity
@Table(name = "api_tokens")
public class ApiToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	private String name;

	@Column(name = "token_hash")
	private String tokenHash;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "last_used")
	private Instant lastUsed;

	protected ApiToken() {
	}

	public ApiToken(Long userId, String name, String tokenHash) {
		this.userId = userId;
		this.name = name;
		this.tokenHash = tokenHash;
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

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLastUsed() {
		return lastUsed;
	}

	public void setLastUsed(Instant lastUsed) {
		this.lastUsed = lastUsed;
	}

}
