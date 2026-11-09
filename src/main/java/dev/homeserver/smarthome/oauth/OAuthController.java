package dev.homeserver.smarthome.oauth;

import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.homeserver.smarthome.audit.Via;
import dev.homeserver.smarthome.oauth.OAuthService.ConsentView;
import dev.homeserver.smarthome.oauth.OAuthService.LogoutView;
import dev.homeserver.smarthome.security.UserAuthentication;
import dev.homeserver.smarthome.user.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * The login, consent and logout pages of the web interface for SSO with Hydra ({@code /oauth/login} ...) call these.
 * Login and consent need the person's own Kratos JWT; logout needs no sign-in (the Kratos session may be over already),
 * the challenge from Hydra is the secret. The answers are always the address to send the browser to next.
 */
@RestController
@RequestMapping("/api/oauth")
public class OAuthController {

	private final OAuthService service;

	public OAuthController(OAuthService service) {
		this.service = service;
	}

	/** The person is signed in with Kratos: Hydra's login request is accepted (or rejected for a deactivated account). */
	@PostMapping("/login")
	public Redirect login(@AuthenticationPrincipal CurrentUser me, Principal principal,
			@RequestHeader(name = Via.HEADER, required = false) String client, @Valid @RequestBody ChallengeRequest request) {
		return new Redirect(service.login(me, Via.of(me, client), aal(principal), request.challenge()));
	}

	/** What to ask the person; {@code redirectTo} instead of the question for trusted own services. */
	@GetMapping("/consent")
	public ConsentView consent(@AuthenticationPrincipal CurrentUser me,
			@RequestHeader(name = Via.HEADER, required = false) String client, @RequestParam @NotBlank String challenge) {
		return service.consent(me, Via.of(me, client), challenge);
	}

	@PostMapping("/consent/accept")
	public Redirect acceptConsent(@AuthenticationPrincipal CurrentUser me,
			@RequestHeader(name = Via.HEADER, required = false) String client, @Valid @RequestBody ConsentRequest request) {
		return new Redirect(service.acceptConsent(me, Via.of(me, client), request.challenge(), request.scopes()));
	}

	@PostMapping("/consent/reject")
	public Redirect rejectConsent(@AuthenticationPrincipal CurrentUser me,
			@RequestHeader(name = Via.HEADER, required = false) String client, @Valid @RequestBody ChallengeRequest request) {
		return new Redirect(service.rejectConsent(me, Via.of(me, client), request.challenge()));
	}

	/** Public (no JWT): which service wants the person signed out. */
	@GetMapping("/logout")
	public LogoutView logout(@RequestParam @NotBlank String challenge) {
		return service.logout(challenge);
	}

	/** Public (no JWT), like the page: the person confirmed, Hydra ends its session and sends them on. */
	@PostMapping("/logout/accept")
	public Redirect acceptLogout(@Valid @RequestBody ChallengeRequest request) {
		return new Redirect(service.acceptLogout(request.challenge()));
	}

	/** The person does not want to be signed out: nothing happens, 204. */
	@PostMapping("/logout/reject")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void rejectLogout(@Valid @RequestBody ChallengeRequest request) {
		service.rejectLogout(request.challenge());
	}

	public record ChallengeRequest(@NotBlank String challenge) {
	}

	public record ConsentRequest(@NotBlank String challenge, List<String> scopes) {
	}

	public record Redirect(String redirectTo) {
	}

	/** The Kratos authenticator assurance level of the JWT ("aal1", "aal2"), null for anything else. */
	private static String aal(Principal principal) {
		if (principal instanceof UserAuthentication auth && auth.getCredentials() != null) {
			return auth.getCredentials().getClaimAsString("aal");
		}
		return null;
	}

}
