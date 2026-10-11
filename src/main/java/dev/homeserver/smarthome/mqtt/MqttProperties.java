package dev.homeserver.smarthome.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("smarthome.mqtt")
public record MqttProperties(String url, String username, String password, String clientId, String z2mBaseTopic) {
}
