package dev.homeserver.smarthome.z2m;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One entry of Zigbee2MQTT's retained {@code bridge/devices} message (only the fields we use). */
record Z2mBridgeDevice(
		@JsonProperty("ieee_address") String ieeeAddress,
		@JsonProperty("friendly_name") String friendlyName,
		String type,
		Boolean disabled,
		Definition definition) {

	record Definition(String vendor, String model, String description, List<Map<String, Object>> exposes) {
	}

}
