package dev.homeserver.smarthome.confirmation;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import dev.homeserver.smarthome.audit.Via;
import dev.homeserver.smarthome.grant.Action;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A command that waits for someone to approve it, e.g. unlocking the door. */
@Entity
@Table(name = "confirmations")
public class Confirmation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Who asked. */
	@Column(name = "user_id")
	private Long userId;

	@Column(name = "device_id")
	private Long deviceId;

	private String action;

	/** The command to send once approved. */
	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> params = new HashMap<>();

	private String status;

	private String via;

	@Column(name = "decided_by")
	private Long decidedBy;

	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	protected Confirmation() {
	}

	Confirmation(long userId, long deviceId, Action action, Map<String, Object> command, Via via, Instant expiresAt) {
		this.userId = userId;
		this.deviceId = deviceId;
		this.action = action.dbValue();
		this.params = new HashMap<>(command);
		this.status = ConfirmationStatus.PENDING.dbValue();
		this.via = via.name().toLowerCase();
		this.createdAt = Instant.now();
		this.expiresAt = expiresAt;
	}

	void decide(ConfirmationStatus status, Long decidedBy) {
		this.status = status.dbValue();
		this.decidedBy = decidedBy;
		this.decidedAt = Instant.now();
	}

	boolean isDue(Instant now) {
		return getStatus() == ConfirmationStatus.PENDING && !expiresAt.isAfter(now);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Long getDeviceId() {
		return deviceId;
	}

	public Action getAction() {
		return Action.valueOf(action.toUpperCase());
	}

	public Map<String, Object> getParams() {
		return params;
	}

	public ConfirmationStatus getStatus() {
		return ConfirmationStatus.fromDb(status);
	}

	public Via getVia() {
		return Via.valueOf(via.toUpperCase());
	}

	public Long getDecidedBy() {
		return decidedBy;
	}

	public Instant getDecidedAt() {
		return decidedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

}
