package dev.homeserver.smarthome;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
	}

	@Bean
	GenericContainer<?> mosquittoContainer() {
		return new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
				.withCommand("mosquitto", "-c", "/mosquitto-no-auth.conf")
				.withExposedPorts(1883);
	}

	@Bean
	DynamicPropertyRegistrar mqttProperties(GenericContainer<?> mosquittoContainer) {
		return registry -> registry.add("smarthome.mqtt.url", () -> mqttUrl(mosquittoContainer));
	}

	@Bean
	DynamicPropertyRegistrar authProperties() {
		return registry -> {
			registry.add("smarthome.auth.jwk-set-uri", TestJwts::jwkSetUri);
			registry.add("smarthome.auth.issuer", () -> TestJwts.ISSUER);
			registry.add("smarthome.auth.initial-admin", () -> "admin@example.com");
		};
	}

	public static String mqttUrl(GenericContainer<?> mosquitto) {
		return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
	}

}
