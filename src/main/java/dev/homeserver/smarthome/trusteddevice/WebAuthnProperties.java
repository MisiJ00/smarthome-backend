package dev.homeserver.smarthome.trusteddevice;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The web interface as WebAuthn relying party. Credentials only work for this host name, and only requests from one of
 * the origins are accepted.
 *
 * @param rpId    host name of the web interface without port (e.g. {@code localhost}, {@code home.example.org})
 * @param rpName  name the authenticator shows
 * @param origins addresses of the web interface as the browser reports them (e.g. {@code https://home.example.org})
 */
@ConfigurationProperties("smarthome.webauthn")
public record WebAuthnProperties(String rpId, String rpName, List<String> origins) {

	public WebAuthnProperties {
		if (rpId == null || rpId.isBlank()) {
			rpId = "localhost";
		}
		if (rpName == null || rpName.isBlank()) {
			rpName = "Smart Home";
		}
		origins = origins == null || origins.isEmpty() ? List.of("http://localhost:5173")
				: origins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
	}

}
