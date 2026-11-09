package dev.homeserver.smarthome.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param adminUrl Hydra admin API, only reachable inside the Docker network (e.g. {@code http://hydra:4445/}); empty
 *                 means SSO is not set up
 */
@ConfigurationProperties("smarthome.hydra")
public record HydraProperties(String adminUrl) {
}
