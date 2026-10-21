package dev.homeserver.smarthome.z2m;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import dev.homeserver.smarthome.device.DeviceAdapter;
import dev.homeserver.smarthome.error.ApiException;
import dev.homeserver.smarthome.device.DeviceStateChanged;
import dev.homeserver.smarthome.device.DiscoveredDevice;
import dev.homeserver.smarthome.events.LiveEvents.DeviceDiscovered;
import dev.homeserver.smarthome.events.LiveEvents.PairingChanged;
import dev.homeserver.smarthome.mqtt.MqttProperties;
import dev.homeserver.smarthome.mqtt.MqttPublisher;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to Zigbee2MQTT. Devices are identified by their IEEE address, because the
 * friendly name used in topics can be renamed in Zigbee2MQTT at any time.
 */
@Component
public class Z2mAdapter implements DeviceAdapter {

	public static final String NAME = "z2m";

	private static final Logger log = LoggerFactory.getLogger(Z2mAdapter.class);

	private final MqttPublisher publisher;
	private final ApplicationEventPublisher events;
	private final JsonMapper json;
	private final String base;

	// Keyed by IEEE address
	private volatile Map<String, Z2mBridgeDevice> devices = Map.of();
	private volatile boolean devicesLoaded;
	private volatile Map<String, String> ieeeByFriendlyName = Map.of();
	private final Map<String, Map<String, Object>> states = new ConcurrentHashMap<>();

	/** Bridge requests waiting for their response, keyed by transaction. */
	private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
	private volatile PermitJoin permitJoin = new PermitJoin(false, null, false);

	/**
	 * Whether new devices may join, as Zigbee2MQTT last reported it in {@code bridge/info}.
	 *
	 * @param known false until Zigbee2MQTT has reported anything
	 */
	public record PermitJoin(boolean active, Instant endsAt, boolean known) {
	}

	public Z2mAdapter(MqttPublisher publisher, ApplicationEventPublisher events, JsonMapper json,
			MqttProperties properties) {
		this.publisher = publisher;
		this.events = events;
		this.json = json;
		this.base = properties.z2mBaseTopic();
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Collection<DiscoveredDevice> discovered() {
		return devices.values().stream().map(this::toDiscovered).toList();
	}

	@Override
	public void send(String externalId, Map<String, Object> command) {
		var device = devices.get(externalId);
		if (device == null) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "device.unavailable",
					"Zigbee2MQTT kennt das Gerät gerade nicht. Ist es noch verbunden?");
		}
		publisher.publish(base + "/" + device.friendlyName() + "/set", json.writeValueAsString(command));
	}

	public PermitJoin permitJoin() {
		return permitJoin;
	}

	/**
	 * Sends a request to {@code bridge/request/<name>}; the future completes with Zigbee2MQTT's response
	 * ({@code {"status": "ok"|"error", "data": ..., "error": ...}}).
	 */
	public CompletableFuture<Map<String, Object>> request(String name, Map<String, Object> payload) {
		String transaction = UUID.randomUUID().toString();
		var response = new CompletableFuture<Map<String, Object>>();
		pending.put(transaction, response);
		var body = new LinkedHashMap<>(payload);
		body.put("transaction", transaction);
		try {
			publisher.publish(base + "/bridge/request/" + name, json.writeValueAsString(body));
		}
		catch (RuntimeException e) {
			pending.remove(transaction);
			throw e;
		}
		return response.whenComplete((result, error) -> pending.remove(transaction));
	}

	/** Entry point for every message below the base topic. */
	void handle(String topic, String payload) {
		if (topic == null || !topic.startsWith(base + "/")) {
			return;
		}
		var rest = topic.substring(base.length() + 1);
		try {
			if (rest.equals("bridge/devices")) {
				onBridgeDevices(payload);
				return;
			}
			if (rest.equals("bridge/info")) {
				onBridgeInfo(payload);
				return;
			}
			if (rest.startsWith("bridge/response/")) {
				onResponse(payload);
				return;
			}
			var ieee = ieeeByFriendlyName.get(rest);
			if (ieee != null) {
				onState(ieee, payload);
			}
			// Everything else (other bridge topics, /set echoes, availability) is not needed yet
		}
		catch (RuntimeException e) {
			log.warn("Ignoring unreadable message on {}: {}", topic, e.getMessage());
		}
	}

	private void onBridgeDevices(String payload) {
		List<Z2mBridgeDevice> all = json.readValue(payload, new TypeReference<>() {
		});
		var usable = all.stream()
				.filter(d -> !"Coordinator".equals(d.type()) && !Boolean.TRUE.equals(d.disabled()) && d.definition() != null)
				.toList();
		var previous = devices;
		devices = usable.stream()
				.collect(Collectors.toUnmodifiableMap(Z2mBridgeDevice::ieeeAddress, d -> d));
		ieeeByFriendlyName = usable.stream()
				.collect(Collectors.toUnmodifiableMap(Z2mBridgeDevice::friendlyName, Z2mBridgeDevice::ieeeAddress));
		log.info("Zigbee2MQTT reports {} devices", usable.size());
		// After the first list, devices that were not there before have just been paired
		if (devicesLoaded) {
			usable.stream()
					.filter(d -> !previous.containsKey(d.ieeeAddress()))
					.forEach(d -> events.publishEvent(new DeviceDiscovered(toDiscovered(d))));
		}
		devicesLoaded = true;

		// Zigbee2MQTT does not retain states by default, so ask every device once
		for (var device : usable) {
			var request = Exposes.getRequest(exposes(device));
			if (!request.isEmpty()) {
				publisher.publish(base + "/" + device.friendlyName() + "/get", json.writeValueAsString(request));
			}
		}
	}

	/** Zigbee2MQTT 2.x reports {@code permit_join_end} (epoch ms), 1.x {@code permit_join_timeout} (seconds left). */
	private void onBridgeInfo(String payload) {
		Map<String, Object> info = json.readValue(payload, new TypeReference<>() {
		});
		boolean active = Boolean.TRUE.equals(info.get("permit_join"));
		Instant endsAt = null;
		if (active && info.get("permit_join_end") instanceof Number end) {
			endsAt = Instant.ofEpochMilli(end.longValue());
		}
		else if (active && info.get("permit_join_timeout") instanceof Number left) {
			endsAt = Instant.now().plusSeconds(left.longValue());
		}
		var before = permitJoin;
		permitJoin = new PermitJoin(active, endsAt, true);
		if (before.active() != active || (active && !Objects.equals(before.endsAt(), endsAt))) {
			events.publishEvent(new PairingChanged(NAME, active, endsAt));
		}
	}

	private void onResponse(String payload) {
		Map<String, Object> response = json.readValue(payload, new TypeReference<>() {
		});
		var waiting = pending.get(String.valueOf(response.get("transaction")));
		if (waiting != null) {
			waiting.complete(response);
		}
	}

	private void onState(String ieee, String payload) {
		Map<String, Object> state = json.readValue(payload, new TypeReference<>() {
		});
		states.merge(ieee, state, (old, update) -> {
			var merged = new LinkedHashMap<>(old);
			merged.putAll(update);
			return merged;
		});
		events.publishEvent(new DeviceStateChanged(NAME, ieee, state));
	}

	DiscoveredDevice toDiscovered(Z2mBridgeDevice device) {
		var definition = device.definition();
		return new DiscoveredDevice(
				NAME,
				device.ieeeAddress(),
				device.friendlyName(),
				Exposes.deviceType(exposes(device)),
				definition.vendor(),
				definition.model(),
				definition.description(),
				Map.of("exposes", exposes(device)),
				states.getOrDefault(device.ieeeAddress(), Map.of()));
	}

	private static List<Map<String, Object>> exposes(Z2mBridgeDevice device) {
		var exposes = device.definition().exposes();
		return exposes == null ? List.of() : exposes;
	}

}
