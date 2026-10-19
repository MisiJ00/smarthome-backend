package dev.homeserver.smarthome.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import dev.homeserver.smarthome.user.UserService;

/**
 * Checks the JWTs that Kratos issues for a session ({@code /sessions/whoami?tokenize_as=smarthome}) and maps them to
 * the backend's own users. Kratos does not publish the tokenizer keys, so the public key set is read once at startup
 * from a file (or URL). After a key change the backend has to be restarted.
 */
@Configuration
public class KratosJwtConfig {

	@Bean
	JwtDecoder jwtDecoder(AuthProperties properties, ResourceLoader resourceLoader) throws IOException, ParseException {
		JWKSet keys;
		try (InputStream in = resourceLoader.getResource(properties.jwkSetUri()).getInputStream()) {
			keys = JWKSet.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		var processor = new DefaultJWTProcessor<SecurityContext>();
		processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, new ImmutableJWKSet<>(keys.toPublicJWKSet())));
		// Expiry and issuer are checked by Spring's validators below
		processor.setJWTClaimsSetVerifier((claims, context) -> {
		});
		var decoder = new NimbusJwtDecoder(processor);
		String issuer = properties.issuer();
		decoder.setJwtValidator(issuer == null || issuer.isBlank()
				? JwtValidators.createDefault()
				: JwtValidators.createDefaultWithIssuer(issuer));
		return decoder;
	}

	@Bean
	Converter<Jwt, AbstractAuthenticationToken> kratosJwtConverter(UserService users) {
		return jwt -> {
			UUID identityId;
			try {
				identityId = UUID.fromString(jwt.getSubject());
			}
			catch (IllegalArgumentException | NullPointerException ex) {
				throw new InvalidBearerTokenException("Subject is not a Kratos identity id");
			}
			String email = jwt.getClaimAsString("email");
			String name = firstNonBlank(jwt.getClaimAsString("name"), jwt.getClaimAsString("preferred_username"), email,
					jwt.getSubject());
			return new UserAuthentication(users.signIn(identityId, name, email), jwt);
		};
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		throw new IllegalArgumentException("No value");
	}

}
