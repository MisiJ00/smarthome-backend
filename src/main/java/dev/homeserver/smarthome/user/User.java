package dev.homeserver.smarthome.user;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

/** A person (or machine) using the backend. Who they are is decided by Kratos, what they may do is decided here. */
@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "identity_id")
	private UUID identityId;

	private String name;

	/** Copy of the Kratos trait, null for machine users. */
	private String email;

	/** Copy of the Kratos trait, null for machine users. */
	private String username;

	@Column(name = "is_active")
	private boolean active;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@ManyToMany(fetch = FetchType.EAGER)
	@JoinTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
	private Set<Role> roles = new HashSet<>();

	protected User() {
	}

	/** A person whose Kratos identity has just been created by an admin. */
	public User(UUID identityId, String name, String email, String username) {
		this.identityId = identityId;
		this.name = name;
		this.email = email;
		this.username = username;
		this.active = true;
	}

	public Long getId() {
		return id;
	}

	public UUID getIdentityId() {
		return identityId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Set<Role> getRoles() {
		return roles;
	}

	/** Machine users (AI, scripts) sign in with API tokens instead of Kratos. */
	public boolean isMachine() {
		return identityId == null;
	}

	public boolean hasRole(String role) {
		return roles.stream().anyMatch(r -> r.getName().equals(role));
	}

}
