package dev.homeserver.smarthome.z2m;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import dev.homeserver.smarthome.audit.AuditResult;
import dev.homeserver.smarthome.audit.AuditService;
import dev.homeserver.smarthome.audit.Via;
import dev.homeserver.smarthome.error.ApiException;
import dev.homeserver.smarthome.mqtt.MqttProperties;
import dev.homeserver.smarthome.user.CurrentUser;
import dev.homeserver.smarthome.z2m.Z2mAdapter.PermitJoin;

/**
 * Pairing mode ("permit join") in Zigbee2MQTT: while it is open, new Zigbee devices can join. They then appear in
 * {@code GET /api/devices/discovered} and as {@code device.discovered} live event, and are added from there.
 */
@Service
public class PairingService {

	/** Zigbee2MQTT's maximum; a longer time would mean "until stopped". */
	public static final int MAX_SECONDS = 254;

	private final Z2mAdapter adapter;
	private final AuditService audit;
	private final MqttProperties properties;

	public PairingService(Z2mAdapter adapter, AuditService audit, MqttProperties properties) {
		this.adapter = adapter;
		this.audit = audit;
		this.properties = properties;
	}

	public PermitJoin status() {
		return adapter.permitJoin();
	}

	/** Opens pairing for {@code seconds} (1 to 254). 504 if Zigbee2MQTT does not answer, 502 if it refuses. */
	public PermitJoin start(CurrentUser user, Via via, int seconds) {
		if (seconds < 1 || seconds > MAX_SECONDS) {
			throw ApiException.badRequest("pairing.seconds_invalid",
					"Die Dauer muss zwischen 1 und " + MAX_SECONDS + " Sekunden liegen.");
		}
		send(seconds);
		audit.record(user, via, null, "permit_join", Map.of("adapter", Z2mAdapter.NAME, "seconds", seconds),
				AuditResult.STARTED, null);
		// bridge/info with the exact end follows shortly
		var reported = adapter.permitJoin();
		return reported.active() && reported.endsAt() != null ? reported
				: new PermitJoin(true, Instant.now().plusSeconds(seconds), reported.known());
	}

	public PermitJoin stop(CurrentUser user, Via via) {
		send(0);
		audit.record(user, via, null, "permit_join", Map.of("adapter", Z2mAdapter.NAME, "seconds", 0),
				AuditResult.STOPPED, null);
		return new PermitJoin(false, null, adapter.permitJoin().known());
	}

	private void send(int seconds) {
		Map<String, Object> response;
		try {
			response = adapter.request("permit_join", Map.of("time", seconds))
					.get(properties.z2mRequestTimeout().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "zigbee2mqtt.no_answer", "Zigbee2MQTT antwortet nicht.");
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted", "Abgebrochen.");
		}
		catch (ExecutionException | RuntimeException e) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "mqtt.unavailable", "MQTT ist gerade nicht erreichbar.");
		}
		if (!"ok".equals(response.get("status"))) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "zigbee2mqtt.refused",
					"Zigbee2MQTT hat abgelehnt: " + response.getOrDefault("error", "unbekannter Fehler"));
		}
	}

}
