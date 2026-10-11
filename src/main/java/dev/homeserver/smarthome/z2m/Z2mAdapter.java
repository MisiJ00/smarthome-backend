package dev.homeserver.smarthome.z2m;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import dev.homeserver.smarthome.device.DeviceAdapter;
import dev.homeserver.smarthome.device.DeviceStateChanged;
import dev.homeserver.smarthome.device.DiscoveredDevice;
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
	private volatile Map<String, String> ieeeByFriendlyName = Map.of();
	private final Map<String, Map<String, Object>> states = new ConcurrentHashMap<>();

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
			throw new IllegalStateException("Zigbee2MQTT does not know device " + externalId);
		}
		publisher.publish(base + "/" + device.friendlyName() + "/set", json.writeValueAsString(command));
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
		devices = usable.stream()
				.collect(Collectors.toUnmodifiableMap(Z2mBridgeDevice::ieeeAddress, d -> d));
		ieeeByFriendlyName = usable.stream()
				.collect(Collectors.toUnmodifiableMap(Z2mBridgeDevice::friendlyName, Z2mBridgeDevice::ieeeAddress));
		log.info("Zigbee2MQTT reports {} devices", usable.size());

		// Zigbee2MQTT does not retain states by default, so ask every device once
		for (var device : usable) {
			var request = Exposes.getRequest(exposes(device));
			if (!request.isEmpty()) {
				publisher.publish(base + "/" + device.friendlyName() + "/get", json.writeValueAsString(request));
			}
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

	private DiscoveredDevice toDiscovered(Z2mBridgeDevice device) {
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
