package dev.homeserver.smarthome.mqtt;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param z2mRequestTimeout how long to wait for Zigbee2MQTT to answer a bridge request (e.g. permit join) */
@ConfigurationProperties("smarthome.mqtt")
public record MqttProperties(String url, String username, String password, String clientId, String z2mBaseTopic,
		Duration z2mRequestTimeout) {

	public MqttProperties {
		if (z2mRequestTimeout == null) {
			z2mRequestTimeout = Duration.ofSeconds(5);
		}
	}

}
