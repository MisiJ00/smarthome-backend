package dev.homeserver.smarthome.z2m;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.MqttHeaders;

import dev.homeserver.smarthome.mqtt.MqttProperties;

@Configuration
class Z2mMqttConfig {

	@Bean
	IntegrationFlow z2mInbound(MqttPahoClientFactory factory, MqttProperties properties, Z2mAdapter adapter) {
		var inbound = new MqttPahoMessageDrivenChannelAdapter(properties.clientId() + "-z2m", factory,
				properties.z2mBaseTopic() + "/#");
		inbound.setQos(1);
		return IntegrationFlow.from(inbound)
				.handle(message -> adapter.handle(
						message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC, String.class),
						String.valueOf(message.getPayload())))
				.get();
	}

}
