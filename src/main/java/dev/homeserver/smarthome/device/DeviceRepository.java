package dev.homeserver.smarthome.device;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DeviceRepository extends JpaRepository<Device, Long> {

	Optional<Device> findByAdapterAndExternalId(String adapter, String externalId);

	boolean existsByAdapterAndExternalId(String adapter, String externalId);

	List<Device> findByRoomId(long roomId);

	List<Device> findByRoomIdIsNull();

	long countByRoomId(long roomId);

	/** Number of devices per room, rooms without devices are missing. */
	@Query("SELECT new dev.homeserver.smarthome.device.DeviceRepository$RoomDeviceCount(d.roomId, count(d)) "
			+ "FROM Device d WHERE d.roomId IS NOT NULL GROUP BY d.roomId")
	List<RoomDeviceCount> countPerRoom();

	record RoomDeviceCount(Long roomId, Long deviceCount) {
	}

}
