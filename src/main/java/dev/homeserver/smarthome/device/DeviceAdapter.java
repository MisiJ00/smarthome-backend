package dev.homeserver.smarthome.device;

import java.util.Collection;
import java.util.Map;

/** Connects one device source (Zigbee2MQTT, Shelly, Home Assistant, ...) to the backend. */
public interface DeviceAdapter {

	String name();

	Collection<DiscoveredDevice> discovered();

	/** Sends a command, e.g. {@code {"state": "ON", "brightness": 120}}, to the device. */
	void send(String externalId, Map<String, Object> command);

}
