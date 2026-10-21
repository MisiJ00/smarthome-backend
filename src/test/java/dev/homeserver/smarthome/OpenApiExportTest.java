package dev.homeserver.smarthome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Writes the OpenAPI description to {@code build/openapi/openapi.json}, from which smarthome-app generates its API
 * client ({@code npm run api:update} there).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiExportTest {

	@Autowired
	MockMvc mvc;

	@Test
	void exportsOpenApiDescription() throws Exception {
		String spec = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(spec).contains("/api/devices", "/api/events/ticket");
		Path file = Path.of("build", "openapi", "openapi.json");
		Files.createDirectories(file.getParent());
		Files.writeString(file, spec, StandardCharsets.UTF_8);
	}

}
