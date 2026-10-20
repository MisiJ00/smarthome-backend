package dev.homeserver.smarthome.grant;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Query parameters use the same lower case values as JSON, e.g. {@code ?subjectType=role}. */
@Configuration
class GrantWebConfig implements WebMvcConfigurer {

	@Override
	public void addFormatters(FormatterRegistry registry) {
		registry.addConverter(String.class, SubjectType.class, s -> SubjectType.valueOf(s.strip().toUpperCase()));
		registry.addConverter(String.class, ScopeType.class, s -> ScopeType.valueOf(s.strip().toUpperCase()));
		registry.addConverter(String.class, Action.class, s -> Action.valueOf(s.strip().toUpperCase()));
	}

}
