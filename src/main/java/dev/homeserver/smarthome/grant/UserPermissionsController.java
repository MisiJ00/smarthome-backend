package dev.homeserver.smarthome.grant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import dev.homeserver.smarthome.grant.EffectivePermissionsService.EffectivePermissions;

/** Admin only, like the rest of {@code /api/users}. */
@RestController
public class UserPermissionsController {

	private final EffectivePermissionsService service;

	public UserPermissionsController(EffectivePermissionsService service) {
		this.service = service;
	}

	/** The union of the user's own grants and those of its roles, and what that means for every device. */
	@GetMapping("/api/users/{id}/effective-permissions")
	public EffectivePermissions effectivePermissions(@PathVariable long id) {
		return service.of(id);
	}

}
