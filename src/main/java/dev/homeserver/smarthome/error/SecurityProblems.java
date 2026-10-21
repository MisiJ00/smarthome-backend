package dev.homeserver.smarthome.error;

import java.io.IOException;
import java.util.LinkedHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 and 403 from Spring Security as problem details like every other error (Spring Security answers with an empty
 * body otherwise). The {@code WWW-Authenticate} header of the bearer token entry point stays.
 */
public class SecurityProblems implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
	private final JsonMapper json;

	public SecurityProblems(JsonMapper json) {
		this.json = json;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
			throws IOException {
		bearer.commence(request, response, exception);
		write(response, HttpStatus.UNAUTHORIZED, "auth.required",
				"Bitte anmelden. Das Token fehlt, ist abgelaufen oder ungültig.");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
			throws IOException {
		write(response, HttpStatus.FORBIDDEN, "auth.forbidden", "Dafür fehlen dir die Rechte.");
	}

	/** Written by hand: the plain JSON mapper would nest the code under "properties". */
	private void write(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
		var problem = new LinkedHashMap<String, Object>();
		problem.put("type", "about:blank");
		problem.put("title", Titles.of(status));
		problem.put("status", status.value());
		problem.put("detail", detail);
		problem.put("code", code);
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(json.writeValueAsString(problem));
	}

}
