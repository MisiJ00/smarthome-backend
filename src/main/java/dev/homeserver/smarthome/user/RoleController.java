package dev.homeserver.smarthome.user;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.homeserver.smarthome.user.RoleService.RoleWithCount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Roles (admin only). System roles are fixed, own roles can be added and removed. */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

	private final RoleService service;

	public RoleController(RoleService service) {
		this.service = service;
	}

	@GetMapping
	public List<RoleResponse> list() {
		return service.list().stream().map(RoleResponse::from).toList();
	}

	/** Lower case letters, digits, dash and underscore; 409 if the name exists. */
	@PostMapping
	public ResponseEntity<RoleResponse> create(@Valid @RequestBody CreateRoleRequest request) {
		Role role = service.create(request.name());
		var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(role.getId()).toUri();
		return ResponseEntity.created(location).body(RoleResponse.from(new RoleWithCount(role, 0)));
	}

	/** 409 for system roles and for roles that users still have ({@code userCount}). */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable long id) {
		service.delete(id);
	}

	public record RoleResponse(long id, String name, boolean system, long userCount) {

		static RoleResponse from(RoleWithCount r) {
			return new RoleResponse(r.role().getId(), r.role().getName(), r.role().isSystem(), r.userCount());
		}

	}

	public record CreateRoleRequest(@NotNull String name) {
	}

}
