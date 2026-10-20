package dev.homeserver.smarthome.grant;

import java.util.List;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import dev.homeserver.smarthome.device.DeviceRepository;
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

	public GrantService(GrantRepository grants, RoleRepository roles, UserRepository users, RoomRepository rooms,
			DeviceRepository devices) {
		this.grants = grants;
		this.roles = roles;
		this.users = users;
		this.rooms = rooms;
		this.devices = devices;
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
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No grant with id " + id));
	}

	/** Unlock grants always require a confirmation, whatever the request says. */
	@Transactional
	public Grant create(SubjectType subjectType, long subjectId, ScopeType scopeType, Long scopeId, Action action,
			Boolean requiresConfirmation) {
		boolean subjectExists = subjectType == SubjectType.ROLE ? roles.existsById(subjectId) : users.existsById(subjectId);
		if (!subjectExists) {
			throw badRequest("No " + subjectType.name().toLowerCase() + " with id " + subjectId);
		}
		switch (scopeType) {
			case ALL -> {
				if (scopeId != null) {
					throw badRequest("scopeId must be empty for scope all");
				}
			}
			case ROOM -> {
				if (scopeId == null || !rooms.existsById(scopeId)) {
					throw badRequest("No room with id " + scopeId);
				}
			}
			case DEVICE -> {
				if (scopeId == null || !devices.existsById(scopeId)) {
					throw badRequest("No device with id " + scopeId);
				}
			}
		}
		boolean confirm = action == Action.UNLOCK || Boolean.TRUE.equals(requiresConfirmation);
		boolean duplicate = list(subjectType, subjectId, scopeType, scopeId).stream().anyMatch(g -> g.getAction() == action);
		if (duplicate) {
			throw conflict();
		}
		try {
			return grants.saveAndFlush(new Grant(subjectType, subjectId, scopeType, scopeId, action, confirm));
		}
		catch (DataIntegrityViolationException e) {
			throw conflict();
		}
	}

	@Transactional
	public Grant setRequiresConfirmation(long id, boolean requiresConfirmation) {
		Grant grant = get(id);
		if (grant.getAction() == Action.UNLOCK && !requiresConfirmation) {
			throw badRequest("Unlocking always requires a confirmation");
		}
		grant.setRequiresConfirmation(requiresConfirmation);
		return grant;
	}

	@Transactional
	public void delete(long id) {
		grants.delete(get(id));
	}

	private static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	private static ResponseStatusException conflict() {
		return new ResponseStatusException(HttpStatus.CONFLICT, "This grant already exists");
	}

}
