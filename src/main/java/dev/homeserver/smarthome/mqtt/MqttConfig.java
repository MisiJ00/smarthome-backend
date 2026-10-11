package dev.homeserver.smarthome.mqtt;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;

@Configuration
public class MqttConfig {

	@Bean
	MqttPahoClientFactory mqttClientFactory(MqttProperties properties) {
		var options = new MqttConnectOptions();
		options.setServerURIs(new String[] { properties.url() });
		options.setAutomaticReconnect(true);
		options.setCleanSession(true);
		if (properties.username() != null && !properties.username().isBlank()) {
			options.setUserName(properties.username());
			options.setPassword(properties.password() == null ? new char[0] : properties.password().toCharArray());
		}
		var factory = new DefaultMqttPahoClientFactory();
		factory.setConnectionOptions(options);
		return factory;
	}

	@Bean
	MqttPahoMessageHandler mqttOutbound(MqttPahoClientFactory factory, MqttProperties properties) {
		var handler = new MqttPahoMessageHandler(properties.clientId() + "-out", factory);
		handler.setAsync(true);
		handler.setDefaultQos(1);
		return handler;
	}

}
