package dev.homeserver.smarthome.confirmation;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ChallengeRepository extends JpaRepository<ConfirmationChallenge, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM ConfirmationChallenge c WHERE c.nonce = :nonce")
	Optional<ConfirmationChallenge> findForUpdate(String nonce);

}
