package dev.homeserver.smarthome.device;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A device that has been added to the home (discovered devices live only in the adapters). */
@Entity
@Table(name = "devices")
public class Device {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	@Column(name = "room_id")
	private Long roomId;

	private String type;

	private String adapter;

	@Column(name = "external_id")
	private String externalId;

	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> capabilities = new HashMap<>();

	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> state = new HashMap<>();

	@Column(name = "last_seen")
	private Instant lastSeen;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "locked_actions")
	private List<String> lockedActions = new ArrayList<>();

	protected Device() {
	}

	public Device(DiscoveredDevice discovered, String name, Long roomId) {
		this.name = name;
		this.roomId = roomId;
		this.type = discovered.type();
		this.adapter = discovered.adapter();
		this.externalId = discovered.externalId();
		this.capabilities = new HashMap<>(discovered.capabilities());
		this.state = new HashMap<>(discovered.state());
	}

	public void mergeState(Map<String, Object> update, Instant seenAt) {
		var merged = new HashMap<>(state);
		merged.putAll(update);
		state = merged;
		lastSeen = seenAt;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Long getRoomId() {
		return roomId;
	}

	public void setRoomId(Long roomId) {
		this.roomId = roomId;
	}

	public String getType() {
		return type;
	}

	public String getAdapter() {
		return adapter;
	}

	public String getExternalId() {
		return externalId;
	}

	public Map<String, Object> getCapabilities() {
		return capabilities;
	}

	public Map<String, Object> getState() {
		return state;
	}

	public Instant getLastSeen() {
		return lastSeen;
	}

	public List<String> getLockedActions() {
		return lockedActions;
	}

	public void setLockedActions(List<String> lockedActions) {
		this.lockedActions = new ArrayList<>(lockedActions);
	}

}
