plugins {
	application
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
	implementation("tools.jackson.core:jackson-databind:3.1.5")
}

application {
	mainClass = "dev.homeserver.simulator.Simulator"
}
