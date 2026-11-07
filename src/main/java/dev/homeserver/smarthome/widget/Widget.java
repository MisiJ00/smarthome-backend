package dev.homeserver.smarthome.widget;

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

/**
 * A widget of one user (app start page or phone homescreen) showing a device or a room. Widgets of deleted devices and
 * rooms are removed by database triggers.
 */
@Entity
@Table(name = "widgets")
public class Widget {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	private String kind;

	@Column(name = "target_type")
	private String targetType;

	@Column(name = "target_id")
	private Long targetId;

	/** Free settings of the client, e.g. a label or a size. */
	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> config = new HashMap<>();

	private int position;

	protected Widget() {
	}

	Widget(long userId, WidgetKind kind, TargetType targetType, long targetId, Map<String, Object> config, int position) {
		this.userId = userId;
		this.kind = kind.dbValue();
		this.targetType = targetType.dbValue();
		this.targetId = targetId;
		this.config = new HashMap<>(config);
		this.position = position;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public WidgetKind getKind() {
		return WidgetKind.valueOf(kind.toUpperCase());
	}

	void setKind(WidgetKind kind) {
		this.kind = kind.dbValue();
	}

	public TargetType getTargetType() {
		return TargetType.valueOf(targetType.toUpperCase());
	}

	public Long getTargetId() {
		return targetId;
	}

	public Map<String, Object> getConfig() {
		return config;
	}

	void setConfig(Map<String, Object> config) {
		this.config = new HashMap<>(config);
	}

	public int getPosition() {
		return position;
	}

	void setPosition(int position) {
		this.position = position;
	}

}
