package dev.homeserver.smarthome.trusteddevice;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface WebAuthnChallengeRepository extends JpaRepository<WebAuthnChallenge, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM WebAuthnChallenge c WHERE c.challenge = :challenge")
	Optional<WebAuthnChallenge> findForUpdate(String challenge);

}
