package dev.homeserver.smarthome.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwkSetUri    location of the public keys Kratos signs its JWTs with ({@code file:} or {@code http(s):})
 * @param issuer       expected {@code iss} claim (the Kratos public URL); not checked when empty
 * @param initialAdmin Kratos identity id or email of the person who always gets the admin role
 */
@ConfigurationProperties("smarthome.auth")
public record AuthProperties(String jwkSetUri, String issuer, String initialAdmin) {
}
