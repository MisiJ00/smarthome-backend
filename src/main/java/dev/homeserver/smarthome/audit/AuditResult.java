package dev.homeserver.smarthome.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

/** What became of an action. */
public enum AuditResult {

	/** The command was sent to the device. */
	@JsonProperty("sent")
	SENT,

	/** The user lacks the permission. */
	@JsonProperty("denied")
	DENIED,

	/** The action is locked for the device, for everyone. */
	@JsonProperty("locked")
	LOCKED,

	/** Waiting for a confirmation. */
	@JsonProperty("pending")
	PENDING,

	/** A confirmation was approved and the command sent. */
	@JsonProperty("approved")
	APPROVED,

	@JsonProperty("rejected")
	REJECTED,

	/** Nobody decided in time. */
	@JsonProperty("expired")
	EXPIRED,

	/** A phone was registered for signed approvals. */
	@JsonProperty("registered")
	REGISTERED,

	/** A phone was revoked. */
	@JsonProperty("revoked")
	REVOKED,

	/** Pairing mode was opened. */
	@JsonProperty("started")
	STARTED,

	/** Pairing mode was closed. */
	@JsonProperty("stopped")
	STOPPED;

	public String dbValue() {
		return name().toLowerCase();
	}

	static AuditResult fromDb(String value) {
		return valueOf(value.toUpperCase());
	}

}
