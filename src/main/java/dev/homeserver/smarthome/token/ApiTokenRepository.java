package dev.homeserver.smarthome.token;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

	Optional<ApiToken> findByTokenHash(String tokenHash);

}
