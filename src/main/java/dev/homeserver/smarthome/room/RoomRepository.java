package dev.homeserver.smarthome.room;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoomRepository extends JpaRepository<Room, Long> {

	List<Room> findAllByOrderBySortAscNameAsc();

	boolean existsByNameIgnoreCase(String name);

	boolean existsByNameIgnoreCaseAndIdNot(String name, long id);

	@Query("SELECT coalesce(max(r.sort) + 1, 0) FROM Room r")
	int nextSort();

}
