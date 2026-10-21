package dev.homeserver.smarthome.room;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.homeserver.smarthome.room.RoomService.RoomWithCount;
import dev.homeserver.smarthome.user.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Rooms: everyone signed in reads them (non-admins only rooms with devices they may view), only admins change them.
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

	private final RoomService service;

	public RoomController(RoomService service) {
		this.service = service;
	}

	/** Sorted by {@code sort}, then name. {@code deviceCount} counts the devices the caller may view. */
	@GetMapping
	public List<RoomResponse> list(@AuthenticationPrincipal CurrentUser me) {
		return service.list(me).stream().map(RoomResponse::from).toList();
	}

	/** 404 for rooms the caller does not see in the list. */
	@GetMapping("/{id}")
	public RoomResponse get(@AuthenticationPrincipal CurrentUser me, @PathVariable long id) {
		return RoomResponse.from(service.get(me, id));
	}

	/** Name is trimmed and has to be unique regardless of case (409 otherwise). New rooms go to the end. */
	@PostMapping
	public ResponseEntity<RoomResponse> create(@Valid @RequestBody CreateRoomRequest request) {
		Room room = service.create(request.name(), request.icon());
		var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(room.getId()).toUri();
		return ResponseEntity.created(location).body(RoomResponse.from(new RoomWithCount(room, 0)));
	}

	/** Fields left out stay unchanged; {@code "icon": null} removes the icon. */
	@PatchMapping("/{id}")
	public RoomResponse update(@PathVariable long id, @RequestBody UpdateRoomRequest request) {
		return RoomResponse.from(service.update(id, request.getName(), request.getIcon(),
				request.isIconSet() && request.getIcon() == null, request.getSort()));
	}

	/** New display order; has to contain every room id exactly once (400 otherwise). */
	@PutMapping("/order")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void reorder(@Valid @RequestBody ReorderRequest request) {
		service.reorder(request.roomIds());
	}

	/** Only empty rooms can be deleted; otherwise 409 with {@code deviceCount}. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable long id) {
		service.delete(id);
	}

	public record RoomResponse(long id, String name, String icon, int sort, long deviceCount, Instant createdAt,
			Instant updatedAt) {

		public static RoomResponse from(RoomWithCount r) {
			Room room = r.room();
			return new RoomResponse(room.getId(), room.getName(), room.getIcon(), room.getSort(), r.deviceCount(),
					room.getCreatedAt(), room.getUpdatedAt());
		}

	}

	public record CreateRoomRequest(@NotNull String name, String icon) {
	}

	/** A class instead of a record so that an explicit {@code "icon": null} can be told apart from a missing icon. */
	public static class UpdateRoomRequest {

		private String name;
		private String icon;
		private boolean iconSet;
		private Integer sort;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getIcon() {
			return icon;
		}

		public void setIcon(String icon) {
			this.icon = icon;
			this.iconSet = true;
		}

		boolean isIconSet() {
			return iconSet;
		}

		public Integer getSort() {
			return sort;
		}

		public void setSort(Integer sort) {
			this.sort = sort;
		}

	}

	public record ReorderRequest(@NotNull List<Long> roomIds) {
	}

}
