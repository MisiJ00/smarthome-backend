package dev.homeserver.smarthome.user;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A role such as admin, bewohner, gast or ki. The set of roles comes from the database migrations. */
@Entity
@Table(name = "roles")
public class Role {

	public static final String ADMIN = "admin";

	@Id
	private Long id;

	private String name;

	protected Role() {
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

}
