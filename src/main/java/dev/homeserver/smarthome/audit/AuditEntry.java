package dev.homeserver.smarthome.audit;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Who did what to which device, through which client, and what came of it. Entries are never changed. */
@Entity
@Table(name = "audit_log")
public class AuditEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Instant ts;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "user_name")
	private String userName;

	private String via;

	@Column(name = "device_id")
	private Long deviceId;

	@Column(name = "device_name")
	private String deviceName;

	private String action;

	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> params = new HashMap<>();

	private String result;

	@Column(name = "confirmation_id")
	private Long confirmationId;

	protected AuditEntry() {
	}

	AuditEntry(Long userId, String userName, Via via, Long deviceId, String deviceName, String action,
			Map<String, Object> params, AuditResult result, Long confirmationId) {
		this.ts = Instant.now();
		this.userId = userId;
		this.userName = userName;
		this.via = via.dbValue();
		this.deviceId = deviceId;
		this.deviceName = deviceName;
		this.action = action;
		this.params = new HashMap<>(params);
		this.result = result.dbValue();
		this.confirmationId = confirmationId;
	}

	public Long getId() {
		return id;
	}

	public Instant getTs() {
		return ts;
	}

	public Long getUserId() {
		return userId;
	}

	public String getUserName() {
		return userName;
	}

	public Via getVia() {
		return Via.fromDb(via);
	}

	public Long getDeviceId() {
		return deviceId;
	}

	public String getDeviceName() {
		return deviceName;
	}

	public String getAction() {
		return action;
	}

	public Map<String, Object> getParams() {
		return params;
	}

	public AuditResult getResult() {
		return AuditResult.fromDb(result);
	}

	public Long getConfirmationId() {
		return confirmationId;
	}

}
