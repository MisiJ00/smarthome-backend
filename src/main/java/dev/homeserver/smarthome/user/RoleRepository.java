package dev.homeserver.smarthome.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, Long> {

	Optional<Role> findByName(String name);

	boolean existsByName(String name);

	/**
	 * Locks the admin role row until the end of the transaction, so that two requests cannot remove the last two
	 * admins at the same time.
	 */
	@Query(value = "SELECT id FROM roles WHERE name = 'admin' FOR UPDATE", nativeQuery = true)
	Long lockAdminRole();

	@Query("SELECT count(u) FROM User u JOIN u.roles r WHERE r.id = :roleId")
	long countMembers(long roleId);

}
