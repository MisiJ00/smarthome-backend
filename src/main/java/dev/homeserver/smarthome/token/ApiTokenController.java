package dev.homeserver.smarthome.token;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.homeserver.smarthome.user.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Machine tokens (admin only). */
@RestController
@RequestMapping("/api/tokens")
public class ApiTokenController {

	private final ApiTokenService service;

	public ApiTokenController(ApiTokenService service) {
		this.service = service;
	}

	@GetMapping
	public List<TokenResponse> list() {
		return service.list().stream().map(TokenResponse::from).toList();
	}

	/** Users that can get tokens (the AI, scripts). */
	@GetMapping("/machine-users")
	public List<CurrentUser> machineUsers() {
		return service.machineUsers();
	}

	/** Creates a token for a machine user. The secret is only in this response, it cannot be shown again. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CreatedTokenResponse create(@Valid @RequestBody CreateTokenRequest request) {
		var created = service.create(request.userId(), request.name());
		return new CreatedTokenResponse(TokenResponse.from(created.token()), created.secret());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revoke(@PathVariable long id) {
		service.revoke(id);
	}

	public record CreateTokenRequest(@NotNull Long userId, @NotBlank String name) {
	}

	public record TokenResponse(long id, long userId, String name, Instant createdAt, Instant lastUsed) {

		static TokenResponse from(ApiToken token) {
			return new TokenResponse(token.getId(), token.getUserId(), token.getName(), token.getCreatedAt(),
					token.getLastUsed());
		}

	}

	public record CreatedTokenResponse(TokenResponse token, String secret) {
	}

}
