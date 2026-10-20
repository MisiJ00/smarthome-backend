package dev.homeserver.smarthome.audit;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Query parameters use the same lower case values as JSON, e.g. {@code ?result=denied&via=ki}. */
@Configuration
class AuditWebConfig implements WebMvcConfigurer {

	@Override
	public void addFormatters(FormatterRegistry registry) {
		registry.addConverter(String.class, AuditResult.class, s -> AuditResult.valueOf(s.strip().toUpperCase()));
		registry.addConverter(String.class, Via.class, s -> Via.valueOf(s.strip().toUpperCase()));
	}

}
