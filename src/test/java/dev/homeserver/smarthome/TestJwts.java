package dev.homeserver.smarthome;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Signs JWTs the way the Kratos tokenizer does, with a key that only exists for the tests. */
public final class TestJwts {

	public static final String ISSUER = "http://kratos.test/";

	private static final ECKey KEY = generate();
	private static final Path JWKS_FILE = writePublicKeys();

	private TestJwts() {
	}

	public static String jwkSetUri() {
		return JWKS_FILE.toUri().toString();
	}

	/** A valid token for a Kratos identity with the given traits. */
	public static String token(UUID identityId, String email, String name) {
		return sign(claims(identityId, Map.of("email", email, "name", name, "preferred_username", name.toLowerCase()))
				.build());
	}

	public static JWTClaimsSet.Builder claims(UUID identityId, Map<String, Object> traits) {
		Instant now = Instant.now();
		var claims = new JWTClaimsSet.Builder()
				.subject(identityId.toString())
				.issuer(ISSUER)
				.jwtID(UUID.randomUUID().toString())
				.claim("sid", UUID.randomUUID().toString())
				.issueTime(Date.from(now))
				.notBeforeTime(Date.from(now))
				.expirationTime(Date.from(now.plusSeconds(300)));
		traits.forEach(claims::claim);
		return claims;
	}

	public static String sign(JWTClaimsSet claims) {
		return sign(KEY, claims);
	}

	/** Signed with a key the backend does not know. */
	public static String signWithForeignKey(JWTClaimsSet claims) {
		return sign(generate(), claims);
	}

	private static String sign(ECKey key, JWTClaimsSet claims) {
		try {
			var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims);
			jwt.sign(new ECDSASigner(key));
			return jwt.serialize();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static ECKey generate() {
		try {
			return new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Path writePublicKeys() {
		try {
			Path file = Files.createTempFile("kratos-jwks", ".json");
			file.toFile().deleteOnExit();
			Files.writeString(file, new JWKSet(KEY.toPublicJWK()).toString());
			return file;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
