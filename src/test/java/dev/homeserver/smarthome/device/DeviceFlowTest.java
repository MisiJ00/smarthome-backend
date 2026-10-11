package dev.homeserver.smarthome.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;

import dev.homeserver.smarthome.TestcontainersConfiguration;

/** Plays Zigbee2MQTT against a real Mosquitto and checks the whole path through the backend. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DeviceFlowTest {

	private static final String BRIDGE_DEVICES = """
			[
			  {"ieee_address": "0x00", "type": "Coordinator", "friendly_name": "Coordinator", "definition": null},
			  {"ieee_address": "0x01", "type": "Router", "friendly_name": "Test Lampe", "disabled": false,
			   "definition": {"vendor": "IKEA", "model": "LED2201G8", "description": "bulb", "exposes": [
			     {"type": "light", "features": [
			       {"type": "binary", "name": "state", "property": "state", "access": 7},
			       {"type": "numeric", "name": "brightness", "property": "brightness", "access": 7}]}]}}
			]
			""";

	@Autowired
	DeviceService service;

	@Autowired
	GenericContainer<?> mosquittoContainer;

	MqttClient zigbee2mqtt;
	Map<String, String> received = new ConcurrentHashMap<>();

	@BeforeEach
	void connectFakeZigbee2mqtt() throws Exception {
		zigbee2mqtt = new MqttClient(TestcontainersConfiguration.mqttUrl(mosquittoContainer), "fake-z2m",
				new MemoryPersistence());
		zigbee2mqtt.connect();
		zigbee2mqtt.subscribe("zigbee2mqtt/+/set", (topic, message) -> received.put(topic, new String(message.getPayload())));
		zigbee2mqtt.subscribe("zigbee2mqtt/+/get", (topic, message) -> received.put(topic, new String(message.getPayload())));
	}

	@AfterEach
	void disconnect() throws Exception {
		zigbee2mqtt.disconnect();
		zigbee2mqtt.close();
	}

	@Test
	void discoversAddsUpdatesAndControlsDevice() throws Exception {
		zigbee2mqtt.publish("zigbee2mqtt/bridge/devices", BRIDGE_DEVICES.getBytes(), 1, true);

		// Discovery: coordinator is ignored, the backend asks the lamp for its state
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(service.discovered()).extracting(DiscoveredDevice::externalId).containsExactly("0x01");
			assertThat(received.get("zigbee2mqtt/Test Lampe/get")).contains("state", "brightness");
		});

		publish("zigbee2mqtt/Test Lampe", "{\"state\": \"OFF\", \"brightness\": 10}");
		await().atMost(Duration.ofSeconds(10))
				.until(() -> !service.discovered().isEmpty() && "OFF".equals(service.discovered().getFirst().state().get("state")));

		// Adding takes over type, capabilities and current state
		var device = service.add("z2m", "0x01", "Wohnzimmer Lampe", null);
		assertThat(device.getType()).isEqualTo("light");
		assertThat(device.getState()).containsEntry("brightness", 10);
		assertThat(service.discovered()).isEmpty();

		// State updates from MQTT end up in the database
		publish("zigbee2mqtt/Test Lampe", "{\"state\": \"ON\"}");
		await().atMost(Duration.ofSeconds(10))
				.untilAsserted(() -> assertThat(service.get(device.getId()).getState())
						.containsEntry("state", "ON")
						.containsEntry("brightness", 10));

		// Commands go out on the /set topic
		service.command(device.getId(), Map.of("brightness", 200));
		await().atMost(Duration.ofSeconds(10))
				.untilAsserted(() -> assertThat(received.get("zigbee2mqtt/Test Lampe/set")).isEqualTo("{\"brightness\":200}"));

		// Locked actions are refused before anything is sent
		service.update(device.getId(), null, null, List.of("switch_off"));
		assertThatThrownBy(() -> service.command(device.getId(), Map.of("state", "OFF")))
				.isInstanceOf(CommandRefusedException.class);
	}

	private void publish(String topic, String payload) throws Exception {
		zigbee2mqtt.publish(topic, payload.getBytes(), 1, false);
	}

}
