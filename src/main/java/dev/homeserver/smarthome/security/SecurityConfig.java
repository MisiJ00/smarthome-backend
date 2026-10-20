package dev.homeserver.smarthome.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.web.SecurityFilterChain;

import dev.homeserver.smarthome.token.ApiTokenService;
import dev.homeserver.smarthome.user.Role;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class SecurityConfig {

	// Every request carries a bearer token, no sessions: a Kratos JWT (people) or an API token (machines).
	// Only health and API docs are public. Everyone signed in can read rooms, only admins change them.
	// Until grants and can() exist, the device API is admin only.
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
			Converter<Jwt, AbstractAuthenticationToken> kratosJwtConverter, ApiTokenService apiTokens) throws Exception {
		var jwtProvider = new JwtAuthenticationProvider(jwtDecoder);
		jwtProvider.setJwtAuthenticationConverter(kratosJwtConverter);
		AuthenticationManager bearerTokens = new ProviderManager(new ApiTokenAuthenticationProvider(apiTokens), jwtProvider);

		return http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
						.requestMatchers("/api/me").authenticated()
						.requestMatchers(HttpMethod.GET, "/api/rooms", "/api/rooms/**").authenticated()
						.requestMatchers("/api/rooms", "/api/rooms/**").hasRole(Role.ADMIN)
						.requestMatchers("/api/devices/**", "/api/tokens/**", "/api/users", "/api/users/**", "/api/roles",
								"/api/roles/**", "/api/grants", "/api/grants/**").hasRole(Role.ADMIN)
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2.authenticationManagerResolver(request -> bearerTokens))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// No cookies are used, so CSRF protection is not needed
				.csrf(csrf -> csrf.disable())
				.build();
	}

	/** Adds the "Authorize" button to Swagger UI (paste the JWT from Kratos or an API token). */
	@Bean
	OpenAPI openApi() {
		return new OpenAPI()
				.components(new Components().addSecuritySchemes("kratos",
						new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
				.addSecurityItem(new SecurityRequirement().addList("kratos"));
	}

}
