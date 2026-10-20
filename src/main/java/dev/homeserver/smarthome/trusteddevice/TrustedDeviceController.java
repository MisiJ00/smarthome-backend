package dev.homeserver.smarthome.trusteddevice;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.homeserver.smarthome.audit.Via;
import dev.homeserver.smarthome.security.UserAuthentication;
import dev.homeserver.smarthome.user.CurrentUser;

/**
 * Phones for approvals with Face ID / fingerprint: everyone manages their own under {@code /api/me}, admins can see
 * and revoke the phones of any user under {@code /api/users/{id}} (for a lost phone).
 */
@RestController
public class TrustedDeviceController {

	private final TrustedDeviceService service;

	public TrustedDeviceController(TrustedDeviceService service) {
		this.service = service;
	}

	@GetMapping("/api/me/trusted-devices")
	public List<TrustedDeviceResponse> mine(@AuthenticationPrincipal CurrentUser me) {
		return service.list(me.id()).stream().map(TrustedDeviceResponse::from).toList();
	}

	/**
	 * Registers this phone's public key (EC P-256, Base64: X.509 SubjectPublicKeyInfo or the raw point
	 * {@code 04||X||Y}). Needs a login at most 15 minutes old, otherwise 403 with {@code reason: fresh_login_required}.
	 * Machine users (the AI) cannot register phones.
	 */
	@PostMapping("/api/me/trusted-devices")
	public ResponseEntity<TrustedDeviceResponse> register(@AuthenticationPrincipal CurrentUser me, Principal principal,
			@RequestHeader(name = Via.HEADER, required = false) String client,
			@RequestBody RegisterRequest request) {
		var device = service.register(me, Via.of(me, client), authTime(principal), request.name(), request.publicKey());
		var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(device.getId())
				.toUri();
		return ResponseEntity.created(location).body(TrustedDeviceResponse.from(device));
	}

	/** Revokes one of the caller's phones; it can no longer sign approvals. */
	@DeleteMapping("/api/me/trusted-devices/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revokeMine(@AuthenticationPrincipal CurrentUser me, @PathVariable long id,
			@RequestHeader(name = Via.HEADER, required = false) String client) {
		service.revoke(me, Via.of(me, client), me.id(), id);
	}

	/** Admin only (path under {@code /api/users}). */
	@GetMapping("/api/users/{userId}/trusted-devices")
	public List<TrustedDeviceResponse> ofUser(@PathVariable long userId) {
		return service.list(userId).stream().map(TrustedDeviceResponse::from).toList();
	}

	/** Admin only, e.g. for a lost phone. */
	@DeleteMapping("/api/users/{userId}/trusted-devices/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revoke(@AuthenticationPrincipal CurrentUser me, @PathVariable long userId, @PathVariable long id,
			@RequestHeader(name = Via.HEADER, required = false) String client) {
		service.revoke(me, Via.of(me, client), userId, id);
	}

	public record RegisterRequest(String name, String publicKey) {
	}

	public record TrustedDeviceResponse(long id, String name, Instant createdAt, Instant lastUsed, Instant revokedAt,
			boolean active) {

		static TrustedDeviceResponse from(TrustedDevice d) {
			return new TrustedDeviceResponse(d.getId(), d.getName(), d.getCreatedAt(), d.getLastUsed(), d.getRevokedAt(),
					!d.isRevoked());
		}

	}

	/** When the caller last signed in to Kratos; null for machine tokens or JWTs without {@code auth_time}. */
	private static Instant authTime(Principal principal) {
		if (principal instanceof UserAuthentication auth && auth.getCredentials() != null) {
			try {
				return auth.getCredentials().getClaimAsInstant("auth_time");
			}
			catch (IllegalArgumentException e) {
				return null;
			}
		}
		return null;
	}

}
