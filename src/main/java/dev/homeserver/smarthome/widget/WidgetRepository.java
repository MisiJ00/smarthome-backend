package dev.homeserver.smarthome.widget;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WidgetRepository extends JpaRepository<Widget, Long> {

	List<Widget> findByUserIdOrderByPositionAscIdAsc(long userId);

	long countByUserId(long userId);

	@Query("SELECT coalesce(max(w.position) + 1, 0) FROM Widget w WHERE w.userId = :userId")
	int nextPosition(long userId);

}
