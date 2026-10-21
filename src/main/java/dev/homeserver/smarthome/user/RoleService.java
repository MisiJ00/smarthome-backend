package dev.homeserver.smarthome.user;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.homeserver.smarthome.error.ApiException;

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
			throw ApiException.badRequest("role.name_invalid",
					"Rollennamen haben 2 bis 32 Zeichen: Kleinbuchstaben, Ziffern, Bindestrich, Unterstrich.");
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
				.orElseThrow(() -> ApiException.notFound("role.not_found", "Diese Rolle gibt es nicht."));
		if (role.isSystem()) {
			throw ApiException.conflict("role.system",
					"Die Systemrolle „" + role.getName() + "“ kann nicht gelöscht werden.");
		}
		long members = roles.countMembers(id);
		if (members > 0) {
			throw ApiException.conflict("role.in_use", "Die Rolle „" + role.getName() + "“ hat noch " + members
					+ " Benutzer. Bitte ihnen zuerst die Rolle entziehen.")
					.with("userCount", members);
		}
		roles.delete(role);
	}

	private static ApiException nameTaken(String name) {
		return ApiException.conflict("role.name_taken", "Eine Rolle „" + name + "“ gibt es schon.");
	}

}
