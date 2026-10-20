package dev.homeserver.smarthome.confirmation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ConfirmationRepository extends JpaRepository<Confirmation, Long> {

	/** Locks the row, so that two people cannot decide the same confirmation at once. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM Confirmation c WHERE c.id = :id")
	Optional<Confirmation> findForUpdate(long id);

	@Query("SELECT c FROM Confirmation c WHERE c.status = 'pending' AND c.expiresAt <= :now ORDER BY c.id")
	List<Confirmation> findDue(Instant now);

	List<Confirmation> findAllByOrderByIdDesc();

}
