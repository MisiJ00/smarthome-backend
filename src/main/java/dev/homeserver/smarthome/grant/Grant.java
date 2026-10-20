package dev.homeserver.smarthome.grant;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Gives a role or a user one action on all devices, the devices of a room, or one device. Grants disappear together
 * with their role, user, room or device (foreign keys with ON DELETE CASCADE).
 */
@Entity
@Table(name = "grants")
public class Grant {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "role_id")
	private Long roleId;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "scope_type")
	private String scopeType;

	@Column(name = "room_id")
	private Long roomId;

	@Column(name = "device_id")
	private Long deviceId;

	private String action;

	@Column(name = "requires_confirmation")
	private boolean requiresConfirmation;

	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	protected Grant() {
	}

	public Grant(SubjectType subjectType, long subjectId, ScopeType scopeType, Long scopeId, Action action,
			boolean requiresConfirmation) {
		if (subjectType == SubjectType.ROLE) {
			this.roleId = subjectId;
		}
		else {
			this.userId = subjectId;
		}
		this.scopeType = scopeType.dbValue();
		if (scopeType == ScopeType.ROOM) {
			this.roomId = scopeId;
		}
		else if (scopeType == ScopeType.DEVICE) {
			this.deviceId = scopeId;
		}
		this.action = action.dbValue();
		this.requiresConfirmation = requiresConfirmation;
		this.createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public SubjectType getSubjectType() {
		return roleId != null ? SubjectType.ROLE : SubjectType.USER;
	}

	public long getSubjectId() {
		return roleId != null ? roleId : userId;
	}

	public Long getRoleId() {
		return roleId;
	}

	public Long getUserId() {
		return userId;
	}

	public ScopeType getScopeType() {
		return ScopeType.fromDb(scopeType);
	}

	/** Room or device id, null for {@link ScopeType#ALL}. */
	public Long getScopeId() {
		return roomId != null ? roomId : deviceId;
	}

	public Long getRoomId() {
		return roomId;
	}

	public Long getDeviceId() {
		return deviceId;
	}

	public Action getAction() {
		return Action.fromDb(action);
	}

	public boolean isRequiresConfirmation() {
		return requiresConfirmation;
	}

	public void setRequiresConfirmation(boolean requiresConfirmation) {
		this.requiresConfirmation = requiresConfirmation;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
