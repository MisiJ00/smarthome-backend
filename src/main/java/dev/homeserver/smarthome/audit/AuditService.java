package dev.homeserver.smarthome.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.error.ApiException;
import dev.homeserver.smarthome.user.CurrentUser;

/** Writes and searches the audit log. */
@Service
public class AuditService {

	public static final int MAX_PAGE_SIZE = 200;

	/** All filters are optional; {@code from} is inclusive, {@code to} exclusive. */
	public record Filter(Long userId, Long deviceId, String action, AuditResult result, Via via, Instant from,
			Instant to) {
	}

	private final AuditRepository entries;

	public AuditService(AuditRepository entries) {
		this.entries = entries;
	}

	/**
	 * Records an action as part of the caller's transaction. Refused commands are recorded before the exception, from
	 * {@code DeviceService.command}, which has no transaction of its own, so those entries stay.
	 */
	@Transactional
	public void record(CurrentUser user, Via via, Device device, String action, Map<String, Object> params,
			AuditResult result, Long confirmationId) {
		entries.save(new AuditEntry(user == null ? null : user.id(), user == null ? null : user.name(), via,
				device == null ? null : device.getId(), device == null ? null : device.getName(), action, params, result,
				confirmationId));
	}

	/** Newest first. */
	@Transactional(readOnly = true)
	public Page<AuditEntry> search(Filter filter, int page, int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw ApiException.badRequest("audit.page_invalid",
					"Die Seite muss bei 0 beginnen und die Seitengröße zwischen 1 und " + MAX_PAGE_SIZE + " liegen.");
		}
		List<Specification<AuditEntry>> conditions = new ArrayList<>();
		if (filter.userId() != null) {
			conditions.add((root, query, cb) -> cb.equal(root.get("userId"), filter.userId()));
		}
		if (filter.deviceId() != null) {
			conditions.add((root, query, cb) -> cb.equal(root.get("deviceId"), filter.deviceId()));
		}
		if (filter.action() != null) {
			conditions.add((root, query, cb) -> cb.equal(root.get("action"), filter.action()));
		}
		if (filter.result() != null) {
			conditions.add((root, query, cb) -> cb.equal(root.get("result"), filter.result().dbValue()));
		}
		if (filter.via() != null) {
			conditions.add((root, query, cb) -> cb.equal(root.get("via"), filter.via().dbValue()));
		}
		if (filter.from() != null) {
			conditions.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("ts"), filter.from()));
		}
		if (filter.to() != null) {
			conditions.add((root, query, cb) -> cb.lessThan(root.get("ts"), filter.to()));
		}
		var sort = Sort.by(Sort.Order.desc("ts"), Sort.Order.desc("id"));
		return entries.findAll(Specification.allOf(conditions), PageRequest.of(page, size, sort));
	}

}
