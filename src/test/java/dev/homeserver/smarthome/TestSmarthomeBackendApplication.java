package dev.homeserver.smarthome;

import org.springframework.boot.SpringApplication;

public class TestSmarthomeBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(SmarthomeBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
