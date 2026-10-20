package dev.homeserver.smarthome.user;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** System roles (admin, bewohner, gast, ki) are fixed; admins can add and remove their own roles. */
@Service
public class RoleService {

	private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{2,32}");

	private final RoleRepository roles;

	public RoleService(RoleRepository roles) {
		this.roles = roles;
	}

	public record RoleWithCount(Role role, long userCount) {
	}

	/** System roles first, then by name. */
	@Transactional(readOnly = true)
	public List<RoleWithCount> list() {
		return roles.findAll().stream()
				.sorted(Comparator.comparing((Role r) -> !r.isSystem()).thenComparing(Role::getName))
				.map(role -> new RoleWithCount(role, roles.countMembers(role.getId())))
				.toList();
	}

	@Transactional
	public Role create(String name) {
		String trimmed = name == null ? "" : name.strip();
		if (!NAME.matcher(trimmed).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Role names have 2 to 32 characters: lower case letters, digits, - and _");
		}
		if (roles.existsByName(trimmed)) {
			throw nameTaken(trimmed);
		}
		try {
			return roles.saveAndFlush(new Role(trimmed));
		}
		catch (DataIntegrityViolationException e) {
			throw nameTaken(trimmed);
		}
	}

	/** Only own roles that nobody has any more can be deleted; their grants go with them. */
	@Transactional
	public void delete(long id) {
		Role role = roles.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No role with id " + id));
		if (role.isSystem()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "System role " + role.getName() + " cannot be deleted");
		}
		long members = roles.countMembers(id);
		if (members > 0) {
			var e = new ResponseStatusException(HttpStatus.CONFLICT,
					"Role " + role.getName() + " still has " + members + " user(s)");
			e.getBody().setProperty("userCount", members);
			throw e;
		}
		roles.delete(role);
	}

	private static ResponseStatusException nameTaken(String name) {
		return new ResponseStatusException(HttpStatus.CONFLICT, "A role named " + name + " already exists");
	}

}
