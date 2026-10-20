package dev.homeserver.smarthome.trusteddevice;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * EC P-256 keys and ECDSA signatures as phones produce them. Public keys come either as X.509 SubjectPublicKeyInfo
 * (Android Keystore) or as a raw uncompressed point {@code 04 || X || Y} (iOS
 * {@code SecKeyCopyExternalRepresentation}); signatures are DER-encoded SHA256withECDSA (both platforms' default).
 */
final class DeviceKeys {

	private static final ECParameterSpec P256 = p256();

	private DeviceKeys() {
	}

	/** Parses and checks a public key, returns it normalized as Base64 SubjectPublicKeyInfo. */
	static String normalize(String base64) {
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(base64.strip());
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("publicKey is not Base64");
		}
		ECPublicKey key = bytes.length == 65 && bytes[0] == 0x04 ? fromRawPoint(bytes) : fromSpki(bytes);
		if (key.getParams().getCurve().getField().getFieldSize() != 256
				|| !key.getParams().getCurve().equals(P256.getCurve())) {
			throw new IllegalArgumentException("publicKey must be an EC P-256 key");
		}
		return Base64.getEncoder().encodeToString(key.getEncoded());
	}

	/** True if {@code signature} (Base64, DER) is a valid SHA256withECDSA signature of {@code payload}. */
	static boolean verify(String spkiBase64, String payload, String signatureBase64) {
		try {
			var key = fromSpki(Base64.getDecoder().decode(spkiBase64));
			var verifier = Signature.getInstance("SHA256withECDSA");
			verifier.initVerify(key);
			verifier.update(payload.getBytes(StandardCharsets.UTF_8));
			return verifier.verify(Base64.getDecoder().decode(signatureBase64));
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			return false;
		}
	}

	private static ECPublicKey fromSpki(byte[] bytes) {
		try {
			var key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(bytes));
			return (ECPublicKey) key;
		}
		catch (GeneralSecurityException | ClassCastException e) {
			throw new IllegalArgumentException("publicKey is not an EC public key");
		}
	}

	private static ECPublicKey fromRawPoint(byte[] bytes) {
		var x = new BigInteger(1, Arrays.copyOfRange(bytes, 1, 33));
		var y = new BigInteger(1, Arrays.copyOfRange(bytes, 33, 65));
		try {
			return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalArgumentException("publicKey is not a point on P-256");
		}
	}

	private static ECParameterSpec p256() {
		try {
			var parameters = AlgorithmParameters.getInstance("EC");
			parameters.init(new ECGenParameterSpec("secp256r1"));
			return parameters.getParameterSpec(ECParameterSpec.class);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

}
