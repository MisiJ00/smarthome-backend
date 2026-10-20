package dev.homeserver.smarthome.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByIdentityId(UUID identityId);

	List<User> findAllByOrderByNameAscIdAsc();

	/** Creates the user unless it exists. Safe when the first requests of a new user arrive in parallel. */
	@Modifying
	@Query(value = "INSERT INTO users (identity_id, name) VALUES (:identityId, :name) ON CONFLICT (identity_id) DO NOTHING",
			nativeQuery = true)
	void insertIfMissing(UUID identityId, String name);

	@Query("SELECT count(u) FROM User u JOIN u.roles r WHERE r.name = 'admin' AND u.active = true")
	long countActiveAdmins();

}
