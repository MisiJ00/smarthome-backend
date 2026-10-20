package dev.homeserver.smarthome.user;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import dev.homeserver.smarthome.grant.GrantDescriber;
import dev.homeserver.smarthome.grant.GrantRepository;
import dev.homeserver.smarthome.grant.GrantResponse;
import dev.homeserver.smarthome.kratos.KratosAdminClient.SetupCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Users (admin only). Changes that would leave no active admin are refused with 409. */
@RestController
@RequestMapping("/api/users")
public class UserController {

	private final UserAdminService service;
	private final GrantRepository grants;
	private final GrantDescriber describer;

	public UserController(UserAdminService service, GrantRepository grants, GrantDescriber describer) {
		this.service = service;
		this.grants = grants;
		this.describer = describer;
	}

	@GetMapping
	public List<UserResponse> list() {
		return service.list().stream().map(UserResponse::from).toList();
	}

	/** The user with its roles and its own grants (grants of its roles: {@code /effective-permissions}). */
	@GetMapping("/{id}")
	public UserDetailResponse get(@PathVariable long id) {
		User user = service.get(id);
		return new UserDetailResponse(UserResponse.from(user), describer.describe(grants.findByUserIdOrderById(id)));
	}

	/**
	 * Creates the account in Kratos and here. The response contains a setup code that is shown only once: the person
	 * enters it in the app and chooses a password. 409 if email or username are taken.
	 */
	@PostMapping
	public ResponseEntity<CreatedUserResponse> create(@Valid @RequestBody CreateUserRequest request) {
		var created = service.create(request.email(), request.username(), request.name(), request.roles());
		var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
				.buildAndExpand(created.user().getId()).toUri();
		return ResponseEntity.created(location)
				.body(new CreatedUserResponse(UserResponse.from(created.user()), created.setupCode()));
	}

	/** A new setup code, e.g. when the first one expired or the password is forgotten. */
	@PostMapping("/{id}/setup-code")
	public SetupCode setupCode(@PathVariable long id) {
		return service.newSetupCode(id);
	}

	/** Fields left out stay unchanged. {@code "active": false} also ends all sessions of the person. */
	@PatchMapping("/{id}")
	public UserResponse update(@PathVariable long id, @RequestBody UpdateUserRequest request) {
		return UserResponse.from(service.update(id, request.name(), request.active()));
	}

	/** Replaces all roles of the user, e.g. {@code {"roles": ["bewohner"]}}. */
	@PutMapping("/{id}/roles")
	public UserResponse setRoles(@PathVariable long id, @Valid @RequestBody SetRolesRequest request) {
		return UserResponse.from(service.setRoles(id, request.roles()));
	}

	/** Deletes the user and its Kratos identity. Machine users cannot be deleted. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable long id) {
		service.delete(id);
	}

	/** {@code machine}: the AI or a script; signs in with API tokens and has no email or username. */
	public record UserResponse(long id, String name, String email, String username, boolean active, boolean machine,
			List<String> roles, Instant createdAt) {

		static UserResponse from(User user) {
			return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getUsername(), user.isActive(),
					user.isMachine(), user.getRoles().stream().map(Role::getName).sorted().toList(), user.getCreatedAt());
		}

	}

	public record UserDetailResponse(UserResponse user, List<GrantResponse> grants) {
	}

	public record CreatedUserResponse(UserResponse user, SetupCode setupCode) {
	}

	/** Username: 3 to 32 characters, lower case letters, digits, dot, dash and underscore (checked by Kratos). */
	public record CreateUserRequest(@NotBlank String email, @NotBlank String username, @NotBlank String name,
			List<String> roles) {
	}

	public record UpdateUserRequest(String name, Boolean active) {
	}

	public record SetRolesRequest(@NotNull List<String> roles) {
	}

}
