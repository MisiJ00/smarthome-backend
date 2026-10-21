package dev.homeserver.smarthome.grant;

import java.util.List;
import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.error.ApiException;
import dev.homeserver.smarthome.events.LiveEvents.PermissionsChanged;
import dev.homeserver.smarthome.room.RoomRepository;
import dev.homeserver.smarthome.user.RoleRepository;
import dev.homeserver.smarthome.user.UserRepository;

/** Managing grants (admin only). Whether a grant lets someone do something is decided by {@link PermissionResolver}. */
@Service
public class GrantService {

	private final GrantRepository grants;
	private final RoleRepository roles;
	private final UserRepository users;
	private final RoomRepository rooms;
	private final DeviceRepository devices;
	private final ApplicationEventPublisher events;

	public GrantService(GrantRepository grants, RoleRepository roles, UserRepository users, RoomRepository rooms,
			DeviceRepository devices, ApplicationEventPublisher events) {
		this.grants = grants;
		this.roles = roles;
		this.users = users;
		this.rooms = rooms;
		this.devices = devices;
		this.events = events;
	}

	/** Filters are optional; a subject or scope id without its type matches nothing. */
	@Transactional(readOnly = true)
	public List<Grant> list(SubjectType subjectType, Long subjectId, ScopeType scopeType, Long scopeId) {
		return grants.findAll().stream()
				.filter(g -> subjectType == null || g.getSubjectType() == subjectType)
				.filter(g -> subjectId == null || (subjectType != null && g.getSubjectId() == subjectId))
				.filter(g -> scopeType == null || g.getScopeType() == scopeType)
				.filter(g -> scopeId == null || (scopeType != null && Objects.equals(g.getScopeId(), scopeId)))
				.sorted((a, b) -> Long.compare(a.getId(), b.getId()))
				.toList();
	}

	@Transactional(readOnly = true)
	public Grant get(long id) {
		return grants.findById(id)
				.orElseThrow(() -> ApiException.notFound("grant.not_found", "Dieses Recht gibt es nicht."));
	}

	/** Unlock grants always require a confirmation, whatever the request says. */
	@Transactional
	public Grant create(SubjectType subjectType, long subjectId, ScopeType scopeType, Long scopeId, Action action,
			Boolean requiresConfirmation) {
		boolean subjectExists = subjectType == SubjectType.ROLE ? roles.existsById(subjectId) : users.existsById(subjectId);
		if (!subjectExists) {
			throw badRequest(subjectType == SubjectType.ROLE ? "role.not_found" : "user.not_found",
					subjectType == SubjectType.ROLE ? "Diese Rolle gibt es nicht." : "Diesen Benutzer gibt es nicht.");
		}
		switch (scopeType) {
			case ALL -> {
				if (scopeId != null) {
					throw badRequest("grant.scope_invalid", "Bei „alle Geräte“ darf keine scopeId angegeben sein.");
				}
			}
			case ROOM -> {
				if (scopeId == null || !rooms.existsById(scopeId)) {
					throw badRequest("room.not_found", "Diesen Raum gibt es nicht.");
				}
			}
			case DEVICE -> {
				if (scopeId == null || !devices.existsById(scopeId)) {
					throw badRequest("device.not_found", "Dieses Gerät gibt es nicht.");
				}
			}
		}
		boolean confirm = action == Action.UNLOCK || Boolean.TRUE.equals(requiresConfirmation);
		boolean duplicate = list(subjectType, subjectId, scopeType, scopeId).stream().anyMatch(g -> g.getAction() == action);
		if (duplicate) {
			throw conflict();
		}
		try {
			var grant = grants.saveAndFlush(new Grant(subjectType, subjectId, scopeType, scopeId, action, confirm));
			changed(grant);
			return grant;
		}
		catch (DataIntegrityViolationException e) {
			throw conflict();
		}
	}

	@Transactional
	public Grant setRequiresConfirmation(long id, boolean requiresConfirmation) {
		Grant grant = get(id);
		if (grant.getAction() == Action.UNLOCK && !requiresConfirmation) {
			throw badRequest("grant.unlock_needs_confirmation", "Aufschließen braucht immer eine Freigabe.");
		}
		grant.setRequiresConfirmation(requiresConfirmation);
		changed(grant);
		return grant;
	}

	@Transactional
	public void delete(long id) {
		var grant = get(id);
		grants.delete(grant);
		changed(grant);
	}

	/** Tells live clients of the user, or of everyone with the role, that their permissions changed. */
	private void changed(Grant grant) {
		if (grant.getUserId() != null) {
			events.publishEvent(new PermissionsChanged(grant.getUserId(), null));
		}
		else {
			roles.findById(grant.getRoleId())
					.ifPresent(role -> events.publishEvent(new PermissionsChanged(null, role.getName())));
		}
	}

	private static ApiException badRequest(String code, String message) {
		return ApiException.badRequest(code, message);
	}

	private static ApiException conflict() {
		return ApiException.conflict("grant.exists", "Dieses Recht gibt es schon.");
	}

}
