package dev.homeserver.smarthome.user;

import java.util.Set;
import java.util.UUID;

/** The authenticated caller of a request, available via {@code @AuthenticationPrincipal}. */
public record CurrentUser(long id, UUID identityId, String name, String email, Set<String> roles) {
}
