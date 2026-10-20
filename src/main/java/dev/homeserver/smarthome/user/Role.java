package dev.homeserver.smarthome.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A role such as admin, bewohner, gast or ki. These system roles come from the database migrations and cannot be
 * deleted; admins can add their own roles.
 */
@Entity
@Table(name = "roles")
public class Role {

	public static final String ADMIN = "admin";
	public static final String KI = "ki";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	@Column(name = "is_system")
	private boolean system;

	protected Role() {
	}

	public Role(String name) {
		this.name = name;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public boolean isSystem() {
		return system;
	}

}
