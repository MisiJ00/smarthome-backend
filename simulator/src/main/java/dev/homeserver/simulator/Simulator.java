package dev.homeserver.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pretends to be Zigbee2MQTT so the backend can be developed without hardware.
 *
 * <p>Uses the same topics as the real thing: {@code <base>/bridge/state},
 * {@code <base>/bridge/devices}, {@code <base>/<name>} for state,
 * {@code <base>/<name>/set} and {@code <base>/<name>/get} for commands.
 *
 * <p>Configuration via environment: {@code MQTT_URL}, {@code MQTT_USER},
 * {@code MQTT_PASSWORD}, {@code Z2M_BASE_TOPIC}.
 */
public class Simulator implements MqttCallbackExtended {

	private static final int TICK_SECONDS = 10;

	private final JsonMapper json = JsonMapper.builder().build();
	private final MqttClient client;
	private final MqttConnectOptions options;
	private final String base;
	private final Map<String, SimDevice> devicesByName;

	Simulator(String url, String user, String password, String base, List<SimDevice> devices) throws MqttException {
		this.base = base;
		this.devicesByName = devices.stream().collect(Collectors.toMap(d -> d.friendlyName, Function.identity()));
		this.client = new MqttClient(url, "zigbee2mqtt-simulator", new MemoryPersistence());
		this.options = new MqttConnectOptions();
		options.setAutomaticReconnect(true);
		options.setCleanSession(true);
		options.setWill(base + "/bridge/state", "{\"state\":\"offline\"}".getBytes(), 1, true);
		if (user != null && !user.isBlank()) {
			options.setUserName(user);
			options.setPassword(Objects.requireNonNullElse(password, "").toCharArray());
		}
		client.setCallback(this);
	}

	public static void main(String[] args) throws Exception {
		var env = System.getenv();
		var url = env.getOrDefault("MQTT_URL", "tcp://localhost:1883");
		var base = env.getOrDefault("Z2M_BASE_TOPIC", "zigbee2mqtt");
		var devices = List.of(
				new SimLight("0x000b57fffe000001", "Wohnzimmer Deckenlampe"),
				new SimThermostat("0x0ceff6fffe000002", "Wohnzimmer Heizung"),
				new SimPlug("0x00124b0000000003", "Server Steckdose", TICK_SECONDS),
				new SimLock("0x00124b0000000004", "Haustuer"));

		var simulator = new Simulator(url, env.get("MQTT_USER"), env.get("MQTT_PASSWORD"), base, devices);
		simulator.start();
		System.out.printf("Simulator connected to %s with base topic '%s'. Ctrl+C to stop.%n", url, base);
		Thread.currentThread().join();
	}

	void start() throws MqttException {
		client.connect(options);
		var scheduler = Executors.newSingleThreadScheduledExecutor();
		scheduler.scheduleAtFixedRate(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			scheduler.shutdown();
			try {
				publish(base + "/bridge/state", Map.of("state", "offline"), true);
				client.disconnect();
			}
			catch (MqttException ignored) {
				// shutting down anyway
			}
		}));
	}

	@Override
	public void connectComplete(boolean reconnect, String serverURI) {
		try {
			client.subscribe(base + "/+/set", 1);
			client.subscribe(base + "/+/get", 1);
			publish(base + "/bridge/state", Map.of("state", "online"), true);
			var bridgeDevices = new ArrayList<Map<String, Object>>();
			bridgeDevices.add(coordinator());
			devicesByName.values().forEach(d -> bridgeDevices.add(d.bridgeInfo()));
			publish(base + "/bridge/devices", bridgeDevices, true);
			for (var device : devicesByName.values()) {
				publish(base + "/" + device.friendlyName + "/availability", Map.of("state", "online"), true);
				synchronized (device) {
					publishState(device);
				}
			}
		}
		catch (MqttException e) {
			System.err.println("Setup after connect failed: " + e.getMessage());
		}
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) {
		// <base>/<name>/set or <base>/<name>/get
		var rest = topic.substring(base.length() + 1);
		var slash = rest.lastIndexOf('/');
		var device = devicesByName.get(rest.substring(0, slash));
		if (device == null) {
			return;
		}
		var command = rest.substring(slash + 1);
		try {
			Map<String, Object> payload = json.readValue(message.getPayload(), new TypeReference<>() {
			});
			System.out.printf("%s %s %s%n", device.friendlyName, command, payload);
			synchronized (device) {
				if (command.equals("set")) {
					device.apply(payload);
				}
				// Zigbee2MQTT answers both with the current state
				publishState(device);
			}
		}
		catch (Exception e) {
			System.err.printf("Ignoring invalid message on %s: %s%n", topic, e.getMessage());
		}
	}

	@Override
	public void connectionLost(Throwable cause) {
		System.err.println("Connection lost, reconnecting: " + cause.getMessage());
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken token) {
	}

	private void tick() {
		for (var device : devicesByName.values()) {
			synchronized (device) {
				if (device.tick()) {
					try {
						publishState(device);
					}
					catch (MqttException e) {
						System.err.println("Publishing state failed: " + e.getMessage());
					}
				}
			}
		}
	}

	private void publishState(SimDevice device) throws MqttException {
		publish(base + "/" + device.friendlyName, device.state, false);
	}

	private void publish(String topic, Object payload, boolean retained) throws MqttException {
		if (client.isConnected()) {
			client.publish(topic, json.writeValueAsBytes(payload), 1, retained);
		}
	}

	private static Map<String, Object> coordinator() {
		return Map.of(
				"ieee_address", "0x00124b00c0ffee00",
				"type", "Coordinator",
				"network_address", 0,
				"friendly_name", "Coordinator",
				"supported", true,
				"disabled", false,
				"interview_completed", true,
				"interviewing", false);
	}

}
