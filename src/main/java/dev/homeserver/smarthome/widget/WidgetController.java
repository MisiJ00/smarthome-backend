package dev.homeserver.smarthome.widget;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.homeserver.smarthome.user.CurrentUser;
import dev.homeserver.smarthome.widget.WidgetService.HomescreenData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * The caller's own widgets. Switching from a widget uses {@code POST /api/devices/{id}/commands} with
 * {@code X-Smarthome-Client: widget}, so the audit log shows where it came from.
 */
@RestController
@RequestMapping("/api/me/widgets")
public class WidgetController {

	private final WidgetService service;

	public WidgetController(WidgetService service) {
		this.service = service;
	}

	@GetMapping
	public List<WidgetResponse> list(@AuthenticationPrincipal CurrentUser me) {
		return service.list(me).stream().map(WidgetResponse::from).toList();
	}

	/**
	 * Kinds: {@code switch} and {@code dimmer} (lights; switch also for switches), {@code thermostat}, {@code lock},
	 * {@code status} (any device), {@code room}. The target has to be visible to the caller (400 otherwise).
	 */
	@PostMapping
	public ResponseEntity<WidgetResponse> create(@AuthenticationPrincipal CurrentUser me,
			@Valid @RequestBody CreateWidgetRequest request) {
		var widget = service.create(me, request.kind(), request.targetType(), request.targetId(), request.config());
		var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(widget.getId())
				.toUri();
		return ResponseEntity.created(location).body(WidgetResponse.from(widget));
	}

	/** {@code {kind?, config?}}; the target cannot be changed. */
	@PatchMapping("/{id}")
	public WidgetResponse update(@AuthenticationPrincipal CurrentUser me, @PathVariable long id,
			@RequestBody UpdateWidgetRequest request) {
		return WidgetResponse.from(service.update(me, id, request.kind(), request.config()));
	}

	/** Has to contain each of the caller's widgets exactly once (400 otherwise). */
	@PutMapping("/order")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void reorder(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody WidgetOrderRequest request) {
		service.reorder(me, request.widgetIds());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal CurrentUser me, @PathVariable long id) {
		service.delete(me, id);
	}

	/**
	 * Compact data for homescreen widgets (iOS WidgetKit, Android App Widgets): per widget name, the relevant state
	 * and the allowed actions. Widgets whose target the caller may no longer see come with {@code available: false}
	 * and nothing else.
	 */
	@GetMapping("/data")
	public HomescreenData data(@AuthenticationPrincipal CurrentUser me) {
		return service.data(me);
	}

	public record CreateWidgetRequest(@NotNull WidgetKind kind, @NotNull TargetType targetType, @NotNull Long targetId,
			Map<String, Object> config) {
	}

	public record UpdateWidgetRequest(WidgetKind kind, Map<String, Object> config) {
	}

	public record WidgetOrderRequest(@NotNull List<Long> widgetIds) {
	}

	public record WidgetResponse(long id, WidgetKind kind, TargetType targetType, long targetId,
			Map<String, Object> config, int position) {

		static WidgetResponse from(Widget w) {
			return new WidgetResponse(w.getId(), w.getKind(), w.getTargetType(), w.getTargetId(), w.getConfig(),
					w.getPosition());
		}

	}

}
