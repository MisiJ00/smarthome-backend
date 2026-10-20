package dev.homeserver.smarthome.grant;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Grants (admin only): a role or a user may do one action on all devices, a room or a device. */
@RestController
@RequestMapping("/api/grants")
public class GrantController {

	private final GrantService service;
	private final GrantDescriber describer;

	public GrantController(GrantService service, GrantDescriber describer) {
		this.service = service;
		this.describer = describer;
	}

	/** Optional filters, e.g. {@code ?subjectType=role&subjectId=2} or {@code ?scopeType=room&scopeId=5}. */
	@GetMapping
	public List<GrantResponse> list(@RequestParam(required = false) SubjectType subjectType,
			@RequestParam(required = false) Long subjectId, @RequestParam(required = false) ScopeType scopeType,
			@RequestParam(required = false) Long scopeId) {
		return describer.describe(service.list(subjectType, subjectId, scopeType, scopeId));
	}

	@GetMapping("/{id}")
	public GrantResponse get(@PathVariable long id) {
		return describer.describe(service.get(id));
	}

	/** 409 if the same grant exists already. Unlock grants always require a confirmation. */
	@PostMapping
	public ResponseEntity<GrantResponse> create(@Valid @RequestBody CreateGrantRequest request) {
		Grant grant = service.create(request.subjectType(), request.subjectId(), request.scopeType(), request.scopeId(),
				request.action(), request.requiresConfirmation());
		var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(grant.getId()).toUri();
		return ResponseEntity.created(location).body(describer.describe(grant));
	}

	/** Only the confirmation can be changed; for anything else delete the grant and create a new one. */
	@PatchMapping("/{id}")
	public GrantResponse update(@PathVariable long id, @Valid @RequestBody UpdateGrantRequest request) {
		return describer.describe(service.setRequiresConfirmation(id, request.requiresConfirmation()));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable long id) {
		service.delete(id);
	}

	/** {@code scopeId} is the room or device id and stays empty for scope {@code all}. */
	public record CreateGrantRequest(@NotNull SubjectType subjectType, @NotNull Long subjectId,
			@NotNull ScopeType scopeType, Long scopeId, @NotNull Action action, Boolean requiresConfirmation) {
	}

	public record UpdateGrantRequest(@NotNull Boolean requiresConfirmation) {
	}

}
