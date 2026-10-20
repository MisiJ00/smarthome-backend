package dev.homeserver.smarthome.grant;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GrantRepository extends JpaRepository<Grant, Long> {

	/** Grants of the user itself and of all its roles. */
	@Query("SELECT g FROM Grant g WHERE g.userId = :userId OR g.roleId IN :roleIds ORDER BY g.id")
	List<Grant> findForUser(long userId, Collection<Long> roleIds);

	List<Grant> findByUserIdOrderById(long userId);

}
