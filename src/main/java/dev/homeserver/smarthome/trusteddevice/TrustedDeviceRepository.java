package dev.homeserver.smarthome.trusteddevice;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Long> {

	List<TrustedDevice> findByUserIdOrderById(long userId);

	boolean existsByPublicKey(String publicKey);

	boolean existsByUserIdAndRevokedAtIsNull(long userId);

}
