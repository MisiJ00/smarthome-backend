package dev.homeserver.smarthome.device;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, Long> {

	Optional<Device> findByAdapterAndExternalId(String adapter, String externalId);

	boolean existsByAdapterAndExternalId(String adapter, String externalId);

}
