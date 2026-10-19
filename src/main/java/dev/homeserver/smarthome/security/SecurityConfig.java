package dev.homeserver.smarthome.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;

import dev.homeserver.smarthome.user.Role;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class SecurityConfig {

	// Every request carries a Kratos JWT as bearer token, no sessions. Only health and API docs are public.
	// Until grants and can() exist, the device API is admin only.
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, Converter<Jwt, AbstractAuthenticationToken> kratosJwtConverter)
			throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
						.requestMatchers("/api/me").authenticated()
						.requestMatchers("/api/devices/**").hasRole(Role.ADMIN)
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(kratosJwtConverter)))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// No cookies are used, so CSRF protection is not needed
				.csrf(csrf -> csrf.disable())
				.build();
	}

	/** Adds the "Authorize" button to Swagger UI (paste the JWT from Kratos). */
	@Bean
	OpenAPI openApi() {
		return new OpenAPI()
				.components(new Components().addSecuritySchemes("kratos",
						new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
				.addSecurityItem(new SecurityRequirement().addList("kratos"));
	}

}
