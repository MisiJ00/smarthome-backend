package dev.homeserver.smarthome.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.homeserver.smarthome.audit.AuditService.Filter;

/** The audit log (admin only): who switched what when, through which client, and with which result. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

	private final AuditService service;

	public AuditController(AuditService service) {
		this.service = service;
	}

	/**
	 * Newest first, {@code page} from 0, {@code size} up to 200. Filters: {@code userId}, {@code deviceId},
	 * {@code action} (e.g. control, unlock), {@code result}, {@code via}, {@code from}/{@code to} (ISO-8601 instants).
	 */
	@GetMapping
	public AuditPage list(@RequestParam(required = false) Long userId, @RequestParam(required = false) Long deviceId,
			@RequestParam(required = false) String action, @RequestParam(required = false) AuditResult result,
			@RequestParam(required = false) Via via, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		var found = service.search(new Filter(userId, deviceId, action, result, via, from, to), page, size);
		return new AuditPage(found.getContent().stream().map(AuditResponse::from).toList(), page, size,
				found.getTotalElements());
	}

	public record AuditPage(List<AuditResponse> items, int page, int size, long total) {
	}

	/** User and device names are those at the time of the entry; the ids are empty once they are deleted. */
	public record AuditResponse(long id, Instant ts, Long userId, String userName, Via via, Long deviceId,
			String deviceName, String action, Map<String, Object> params, AuditResult result, Long confirmationId) {

		static AuditResponse from(AuditEntry e) {
			return new AuditResponse(e.getId(), e.getTs(), e.getUserId(), e.getUserName(), e.getVia(), e.getDeviceId(),
					e.getDeviceName(), e.getAction(), e.getParams(), e.getResult(), e.getConfirmationId());
		}

	}

}
