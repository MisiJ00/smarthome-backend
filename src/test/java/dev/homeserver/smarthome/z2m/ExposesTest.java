package dev.homeserver.smarthome.z2m;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ExposesTest {

	private static final List<Map<String, Object>> LIGHT = List.of(
			Map.of("type", "light", "features", List.of(
					Map.of("type", "binary", "property", "state", "access", 7),
					Map.of("type", "numeric", "property", "brightness", "access", 7))),
			Map.of("type", "numeric", "property", "linkquality", "access", 1));

	@Test
	void derivesDeviceType() {
		assertThat(Exposes.deviceType(LIGHT)).isEqualTo("light");
		assertThat(Exposes.deviceType(List.of(Map.of("type", "climate")))).isEqualTo("thermostat");
		assertThat(Exposes.deviceType(List.of(Map.of("type", "numeric")))).isEqualTo("sensor");
	}

	@Test
	void getRequestContainsOnlyQueryableProperties() {
		assertThat(Exposes.getRequest(LIGHT)).containsOnlyKeys("state", "brightness");
	}

}
