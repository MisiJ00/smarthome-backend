package dev.homeserver.smarthome.user;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The authenticated caller of a request, available via {@code @AuthenticationPrincipal}. Machine users (the AI,
 * scripts) have no Kratos identity and no email.
 */
public record CurrentUser(long id, UUID identityId, String name, String email, Set<String> roles) {

	public static CurrentUser of(User user, String email) {
		Set<String> roles = user.getRoles().stream().map(Role::getName).collect(Collectors.toUnmodifiableSet());
		return new CurrentUser(user.getId(), user.getIdentityId(), user.getName(), email, roles);
	}

}
