package dev.homeserver.smarthome.mqtt;

import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class MqttPublisher {

	private final MqttPahoMessageHandler handler;

	public MqttPublisher(MqttPahoMessageHandler handler) {
		this.handler = handler;
	}

	public void publish(String topic, String payload) {
		handler.handleMessage(MessageBuilder.withPayload(payload).setHeader(MqttHeaders.TOPIC, topic).build());
	}

}
