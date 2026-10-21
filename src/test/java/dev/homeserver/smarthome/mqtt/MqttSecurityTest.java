package dev.homeserver.smarthome.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;

import dev.homeserver.smarthome.TestcontainersConfiguration;

/**
 * Mosquitto as configured for production (users and ACL from homeserver-infra): nobody gets in anonymously, and the
 * backend can send commands but cannot fake device states.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MqttSecurityTest {

	@Autowired
	GenericContainer<?> mosquittoContainer;

	@Test
	void anonymousAndWrongPasswordsAreRefused() {
		assertThatThrownBy(() -> connect(new MqttConnectOptions())).isInstanceOf(MqttSecurityException.class);
		assertThatThrownBy(() -> connect(login("backend", "wrong"))).isInstanceOf(MqttSecurityException.class);
	}

	@Test
	void backendSendsCommandsButCannotFakeStates() throws Exception {
		List<String> received = new CopyOnWriteArrayList<>();
		MqttClient zigbee2mqtt = connect(TestcontainersConfiguration.zigbee2mqttLogin());
		zigbee2mqtt.subscribe("zigbee2mqtt/acltest/#", (topic, message) -> received.add(topic));
		zigbee2mqtt.subscribe("zigbee2mqtt/acltest", (topic, message) -> received.add(topic));
		MqttClient backend = connect(login("backend", TestcontainersConfiguration.MQTT_BACKEND_PASSWORD));

		// Mosquitto drops forbidden publishes silently (MQTT 3.1.1), so the order shows what got through
		backend.publish("zigbee2mqtt/acltest", "{\"state\":\"FAKE\"}".getBytes(), 1, false);
		backend.publish("zigbee2mqtt/acltest/set", "{\"state\":\"ON\"}".getBytes(), 1, false);
		backend.publish("zigbee2mqtt/bridge/request/permit_join", "{\"time\":0}".getBytes(), 1, false);
		await().atMost(Duration.ofSeconds(5)).until(() -> received.contains("zigbee2mqtt/acltest/set"));
		assertThat(received).containsExactly("zigbee2mqtt/acltest/set");

		backend.disconnect();
		zigbee2mqtt.disconnect();
	}

	@Test
	void zigbee2mqttStaysInItsOwnArea() throws Exception {
		List<String> received = new CopyOnWriteArrayList<>();
		MqttClient backend = connect(login("backend", TestcontainersConfiguration.MQTT_BACKEND_PASSWORD));
		backend.subscribe("zigbee2mqtt/#", (topic, message) -> received.add(topic));
		MqttClient zigbee2mqtt = connect(TestcontainersConfiguration.zigbee2mqttLogin());

		zigbee2mqtt.publish("other/topic", "x".getBytes(), 1, false);
		zigbee2mqtt.publish("zigbee2mqtt/acltest2", "{\"state\":\"ON\"}".getBytes(), 1, false);
		await().atMost(Duration.ofSeconds(5)).until(() -> received.contains("zigbee2mqtt/acltest2"));
		assertThat(received).doesNotContain("other/topic");

		backend.disconnect();
		zigbee2mqtt.disconnect();
	}

	private MqttClient connect(MqttConnectOptions options) throws Exception {
		var client = new MqttClient(TestcontainersConfiguration.mqttUrl(mosquittoContainer),
				"security-test-" + UUID.randomUUID(), new MemoryPersistence());
		client.connect(options);
		return client;
	}

	private static MqttConnectOptions login(String user, String password) {
		var options = new MqttConnectOptions();
		options.setUserName(user);
		options.setPassword(password.toCharArray());
		return options;
	}

}
