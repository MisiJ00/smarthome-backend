package dev.homeserver.smarthome;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
	}

	@Bean
	GenericContainer<?> mosquittoContainer() {
		// Users and ACL as in production (see src/test/resources/mosquitto)
		return new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
				.withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto/"), "/mosquitto/config/")
				.withEnv("MQTT_BACKEND_PASSWORD", MQTT_BACKEND_PASSWORD)
				.withEnv("MQTT_Z2M_PASSWORD", MQTT_Z2M_PASSWORD)
				.withEnv("MQTT_SIMULATOR_PASSWORD", MQTT_SIMULATOR_PASSWORD)
				.withCommand("/bin/sh", "/mosquitto/config/start.sh")
				.withExposedPorts(1883);
	}

	@Bean
	DynamicPropertyRegistrar mqttProperties(GenericContainer<?> mosquittoContainer) {
		return registry -> {
			registry.add("smarthome.mqtt.url", () -> mqttUrl(mosquittoContainer));
			registry.add("smarthome.mqtt.username", () -> "backend");
			registry.add("smarthome.mqtt.password", () -> MQTT_BACKEND_PASSWORD);
		};
	}

	@Bean
	RecordingDeviceAdapter recordingDeviceAdapter() {
		return new RecordingDeviceAdapter();
	}

	/** Real Kratos, in memory, for the admin API (accounts, setup codes) and to redeem setup codes like the app. */
	@Bean
	GenericContainer<?> kratosContainer() {
		return new GenericContainer<>(DockerImageName.parse("oryd/kratos:v26.2.0"))
				.withCopyFileToContainer(MountableFile.forClasspathResource("kratos/"), "/etc/config/kratos/")
				.withCommand("serve", "--config", "/etc/config/kratos/kratos.yml", "--dev", "--sqa-opt-out")
				.withExposedPorts(4433, 4434)
				.waitingFor(Wait.forHttp("/health/ready").forPort(4434));
	}

	@Bean
	DynamicPropertyRegistrar kratosProperties(GenericContainer<?> kratosContainer) {
		return registry -> registry.add("smarthome.kratos.admin-url", () -> kratosAdminUrl(kratosContainer));
	}

	public static String kratosAdminUrl(GenericContainer<?> kratos) {
		return "http://" + kratos.getHost() + ":" + kratos.getMappedPort(4434);
	}

	public static String kratosPublicUrl(GenericContainer<?> kratos) {
		return "http://" + kratos.getHost() + ":" + kratos.getMappedPort(4433);
	}

	@Bean
	DynamicPropertyRegistrar authProperties() {
		return registry -> {
			registry.add("smarthome.auth.jwk-set-uri", TestJwts::jwkSetUri);
			registry.add("smarthome.auth.issuer", () -> TestJwts.ISSUER);
			registry.add("smarthome.auth.initial-admin", () -> "admin@example.com");
		};
	}

	public static final String MQTT_BACKEND_PASSWORD = "test-backend";
	public static final String MQTT_Z2M_PASSWORD = "test-zigbee2mqtt";
	public static final String MQTT_SIMULATOR_PASSWORD = "test-simulator";

	/** Connection options for a test client that plays Zigbee2MQTT. */
	public static MqttConnectOptions zigbee2mqttLogin() {
		var options = new MqttConnectOptions();
		options.setUserName("zigbee2mqtt");
		options.setPassword(MQTT_Z2M_PASSWORD.toCharArray());
		options.setMaxInflight(100);
		return options;
	}

	public static String mqttUrl(GenericContainer<?> mosquitto) {
		return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
	}

}
