package dev.homeserver.smarthome.confirmation;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the expiry check for confirmations. */
@Configuration
@EnableScheduling
class ConfirmationConfig {
}
