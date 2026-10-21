package dev.homeserver.simulator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>Pairing: {@code <base>/bridge/request/permit_join} with {@code {"time": seconds}} is answered on
 * {@code <base>/bridge/response/permit_join}, and {@code <base>/bridge/info} reports {@code permit_join} and
 * {@code permit_join_end}. While pairing is open, a new simulated lamp joins after a few seconds and appears in
 * {@code bridge/devices}, like a real device that was put into pairing mode.
 *
 * <p>Configuration via environment: {@code MQTT_URL}, {@code MQTT_USER},
 * {@code MQTT_PASSWORD}, {@code Z2M_BASE_TOPIC}, {@code SIM_JOIN_DELAY_SECONDS} (default 3).
 */
public class Simulator implements MqttCallbackExtended {

	private static final int TICK_SECONDS = 10;
	private static final String PERMIT_JOIN = "bridge/request/permit_join";

	private final JsonMapper json = JsonMapper.builder().build();
	private final MqttClient client;
	private final MqttConnectOptions options;
	private final String base;
	private final Map<String, SimDevice> devicesByName = new ConcurrentHashMap<>();
	private final int joinDelaySeconds;
	private final AtomicInteger joined = new AtomicInteger();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private ScheduledFuture<?> pairingEnd;
	private ScheduledFuture<?> pairingJoin;

	/** The simulator with its standard devices (lamp, thermostat, server plug, lock). */
	public static Simulator withStandardDevices(String url, String user, String password, String base,
			int joinDelaySeconds) throws MqttException {
		return new Simulator(url, user, password, base, standardDevices(), joinDelaySeconds);
	}

	Simulator(String url, String user, String password, String base, List<SimDevice> devices, int joinDelaySeconds)
			throws MqttException {
		this.base = base;
		this.joinDelaySeconds = joinDelaySeconds;
		devices.forEach(d -> devicesByName.put(d.friendlyName, d));
		this.client = new MqttClient(url, "zigbee2mqtt-simulator", new MemoryPersistence());
		this.options = new MqttConnectOptions();
		options.setAutomaticReconnect(true);
		options.setCleanSession(true);
		// The backend asks every device for its state at once on startup; Paho's default of 10 is too few
		options.setMaxInflight(100);
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
		int joinDelay = Integer.parseInt(env.getOrDefault("SIM_JOIN_DELAY_SECONDS", "3"));
		var simulator = withStandardDevices(url, env.get("MQTT_USER"), env.get("MQTT_PASSWORD"), base, joinDelay);
		simulator.start();
		System.out.printf("Simulator connected to %s with base topic '%s'. Ctrl+C to stop.%n", url, base);
		Thread.currentThread().join();
	}

	private static List<SimDevice> standardDevices() {
		return List.of(
				new SimLight("0x000b57fffe000001", "Wohnzimmer Deckenlampe"),
				new SimThermostat("0x0ceff6fffe000002", "Wohnzimmer Heizung"),
				new SimPlug("0x00124b0000000003", "Server Steckdose", TICK_SECONDS),
				new SimLock("0x00124b0000000004", "Haustuer"));
	}

	public void start() throws MqttException {
		client.connect(options);
		scheduler.scheduleAtFixedRate(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
		Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
	}

	/** Goes offline like Zigbee2MQTT does when it stops. */
	public void stop() {
		scheduler.shutdownNow();
		try {
			if (client.isConnected()) {
				publish(base + "/bridge/state", Map.of("state", "offline"), true);
				client.disconnect();
			}
			client.close();
		}
		catch (MqttException ignored) {
			// shutting down anyway
		}
	}

	@Override
	public void connectComplete(boolean reconnect, String serverURI) {
		try {
			client.subscribe(base + "/+/set", 1);
			client.subscribe(base + "/+/get", 1);
			client.subscribe(base + "/" + PERMIT_JOIN, 1);
			publish(base + "/bridge/state", Map.of("state", "online"), true);
			publishInfo(false, 0);
			publishBridgeDevices();
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
		var rest = topic.substring(base.length() + 1);
		if (rest.equals(PERMIT_JOIN)) {
			onPermitJoin(message.getPayload());
			return;
		}
		// <base>/<name>/set or <base>/<name>/get
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

	/** Opens or closes pairing; while open, a new lamp joins after the join delay. */
	private synchronized void onPermitJoin(byte[] payload) {
		try {
			Map<String, Object> request = json.readValue(payload, new TypeReference<>() {
			});
			int seconds = (int) SimDevice.number(request.get("time"), 0);
			var response = new LinkedHashMap<String, Object>();
			response.put("data", Map.of("time", seconds));
			response.put("status", "ok");
			if (request.containsKey("transaction")) {
				response.put("transaction", request.get("transaction"));
			}
			System.out.printf("permit_join %d s%n", seconds);
			cancelPairing();
			publishInfo(seconds > 0, seconds);
			publish(base + "/bridge/response/permit_join", response, false);
			if (seconds > 0) {
				pairingEnd = scheduler.schedule(this::endPairing, seconds, TimeUnit.SECONDS);
				pairingJoin = scheduler.schedule(this::joinNewDevice, Math.min(joinDelaySeconds, seconds),
						TimeUnit.SECONDS);
			}
		}
		catch (Exception e) {
			System.err.println("Ignoring invalid permit_join request: " + e.getMessage());
		}
	}

	private synchronized void endPairing() {
		cancelPairing();
		try {
			publishInfo(false, 0);
		}
		catch (MqttException e) {
			System.err.println("Publishing bridge/info failed: " + e.getMessage());
		}
	}

	private void cancelPairing() {
		if (pairingEnd != null) {
			pairingEnd.cancel(false);
		}
		if (pairingJoin != null) {
			pairingJoin.cancel(false);
		}
	}

	/** Like a real device: joined, interviewed, then part of bridge/devices. */
	private void joinNewDevice() {
		int n = joined.incrementAndGet();
		var device = new SimLight(String.format("0x00158d00000%05d", n), "Neue Lampe " + n);
		var data = Map.of("friendly_name", device.friendlyName, "ieee_address", device.ieeeAddress);
		try {
			publish(base + "/bridge/event", Map.of("type", "device_joined", "data", data), false);
			devicesByName.put(device.friendlyName, device);
			var interviewed = new LinkedHashMap<String, Object>(data);
			interviewed.put("status", "successful");
			publish(base + "/bridge/event", Map.of("type", "device_interview", "data", interviewed), false);
			publishBridgeDevices();
			synchronized (device) {
				publishState(device);
			}
			System.out.printf("%s joined%n", device.friendlyName);
		}
		catch (MqttException e) {
			System.err.println("Joining a device failed: " + e.getMessage());
		}
	}

	private void publishInfo(boolean permitJoin, int seconds) throws MqttException {
		var info = new LinkedHashMap<String, Object>();
		info.put("version", "2.6.0-simulated");
		info.put("permit_join", permitJoin);
		if (permitJoin) {
			info.put("permit_join_end", System.currentTimeMillis() + seconds * 1000L);
		}
		publish(base + "/bridge/info", info, true);
	}

	private void publishBridgeDevices() throws MqttException {
		var bridgeDevices = new ArrayList<Map<String, Object>>();
		bridgeDevices.add(coordinator());
		devicesByName.values().forEach(d -> bridgeDevices.add(d.bridgeInfo()));
		publish(base + "/bridge/devices", bridgeDevices, true);
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
