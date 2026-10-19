package dev.homeserver.smarthome.user;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class MeController {

	/** The caller as the backend sees them. Permissions are added here once grants exist. */
	@GetMapping
	public CurrentUser me(@AuthenticationPrincipal CurrentUser me) {
		return me;
	}

}
