package dev.homeserver.smarthome.grant;

import java.time.Instant;

/** A grant as the API shows it, with the names of subject and scope for display. */
public record GrantResponse(
		long id,
		SubjectType subjectType,
		long subjectId,
		String subjectName,
		ScopeType scopeType,
		Long scopeId,
		String scopeName,
		Action action,
		boolean requiresConfirmation,
		Instant createdAt) {
}
