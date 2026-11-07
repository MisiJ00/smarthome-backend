package dev.homeserver.smarthome.trusteddevice;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parts of WebAuthn the backend needs to trust a browser's credential: reading what the authenticator made when
 * the credential was created, and checking the signature of an assertion. Only ES256 (ECDSA P-256) is accepted, which
 * is what the web interface asks for; attestation statements are not evaluated (the credential belongs to whoever just
 * signed in, which the registration already requires), but user verification is required both times.
 */
final class WebAuthn {

	static final int FLAG_USER_PRESENT = 0x01;
	static final int FLAG_USER_VERIFIED = 0x04;
	static final int FLAG_ATTESTED_DATA = 0x40;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private WebAuthn() {
	}

	/** What a new credential's attestation object says. */
	record Created(byte[] rpIdHash, int flags, long signCount, byte[] credentialId, String publicKeySpki) {
	}

	/** The fields of clientDataJSON the backend checks. */
	record ClientData(String type, String challenge, String origin, boolean crossOrigin) {
	}

	/** Base64 or Base64url, with or without padding. */
	static byte[] decode(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("value is missing");
		}
		String standard = value.strip().replace('-', '+').replace('_', '/');
		int padding = (4 - standard.length() % 4) % 4;
		return Base64.getDecoder().decode(standard + "=".repeat(padding));
	}

	static String base64url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	static ClientData clientData(byte[] json) {
		try {
			JsonNode node = JSON.readTree(json);
			return new ClientData(node.path("type").asString(""), node.path("challenge").asString(""),
					node.path("origin").asString(""), node.path("crossOrigin").asBoolean(false));
		}
		catch (RuntimeException e) {
			throw new IllegalArgumentException("clientDataJSON is not valid JSON");
		}
	}

	/** Checks type, challenge and origin of clientDataJSON. */
	static void checkClientData(ClientData data, String type, String expectedChallenge, List<String> origins) {
		if (!type.equals(data.type())) {
			throw new IllegalArgumentException("wrong clientData type");
		}
		if (!MessageDigest.isEqual(data.challenge().getBytes(StandardCharsets.UTF_8),
				expectedChallenge.getBytes(StandardCharsets.UTF_8))) {
			throw new IllegalArgumentException("challenge does not match");
		}
		if (data.crossOrigin() || !origins.contains(data.origin())) {
			throw new IllegalArgumentException("origin is not allowed");
		}
	}

	/** Reads authenticator data + COSE key out of a (none or any-format) attestation object. */
	static Created created(byte[] attestationObject) {
		Object decoded = Cbor.read(attestationObject);
		if (!(decoded instanceof Map<?, ?> map) || !(map.get("authData") instanceof byte[] authData)) {
			throw new IllegalArgumentException("attestationObject has no authData");
		}
		if (authData.length < 55) {
			throw new IllegalArgumentException("authData is too short");
		}
		int flags = authData[32] & 0xff;
		if ((flags & FLAG_ATTESTED_DATA) == 0) {
			throw new IllegalArgumentException("authData has no credential");
		}
		long signCount = ByteBuffer.wrap(authData, 33, 4).getInt() & 0xffffffffL;
		int idLength = ((authData[53] & 0xff) << 8) | (authData[54] & 0xff);
		if (idLength == 0 || idLength > 1023 || 55 + idLength >= authData.length) {
			throw new IllegalArgumentException("credential id has a wrong length");
		}
		byte[] credentialId = Arrays.copyOfRange(authData, 55, 55 + idLength);
		Object cose = Cbor.read(authData, 55 + idLength, null);
		return new Created(Arrays.copyOfRange(authData, 0, 32), flags, signCount, credentialId, spkiOf(cose));
	}

	/** An ES256 COSE key (kty 2, alg -7, crv 1) as Base64 SubjectPublicKeyInfo. */
	private static String spkiOf(Object cose) {
		if (!(cose instanceof Map<?, ?> key) || !Long.valueOf(2).equals(key.get(1L)) || !Long.valueOf(-7).equals(key.get(3L))
				|| !Long.valueOf(1).equals(key.get(-1L))) {
			throw new IllegalArgumentException("only ES256 (ECDSA P-256) credentials are supported");
		}
		if (!(key.get(-2L) instanceof byte[] x) || !(key.get(-3L) instanceof byte[] y) || x.length != 32 || y.length != 32) {
			throw new IllegalArgumentException("COSE key has no valid point");
		}
		byte[] point = new byte[65];
		point[0] = 0x04;
		System.arraycopy(x, 0, point, 1, 32);
		System.arraycopy(y, 0, point, 33, 32);
		// Also checks that the point is on the curve
		return DeviceKeys.normalize(Base64.getEncoder().encodeToString(point));
	}

	/** The signature counter of assertion authenticator data (bytes 33 to 36). */
	static long signCount(byte[] authData) {
		return ByteBuffer.wrap(authData, 33, 4).getInt() & 0xffffffffL;
	}

	/** True if {@code signature} (DER) signs {@code authData || SHA-256(clientDataJSON)} with the key. */
	static boolean verifyAssertion(String spkiBase64, byte[] authData, byte[] clientDataJson, byte[] signature) {
		try {
			var key = DeviceKeys.publicKey(spkiBase64);
			var verifier = Signature.getInstance("SHA256withECDSA");
			verifier.initVerify(key);
			verifier.update(authData);
			verifier.update(sha256(clientDataJson));
			return verifier.verify(signature);
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			return false;
		}
	}

}
