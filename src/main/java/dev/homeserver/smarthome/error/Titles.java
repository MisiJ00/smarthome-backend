package dev.homeserver.smarthome.error;

import org.springframework.http.HttpStatusCode;

/** German titles of problem details, one per status. */
public final class Titles {

	private Titles() {
	}

	public static String of(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "Ungültige Anfrage";
			case 401 -> "Nicht angemeldet";
			case 403 -> "Nicht erlaubt";
			case 404 -> "Nicht gefunden";
			case 405 -> "Methode nicht erlaubt";
			case 409 -> "Konflikt";
			case 415 -> "Format nicht unterstützt";
			case 422 -> "Nicht verarbeitbar";
			case 502 -> "Fehler eines anderen Dienstes";
			case 503 -> "Dienst nicht erreichbar";
			case 504 -> "Keine Antwort";
			default -> status.is5xxServerError() ? "Interner Fehler" : "Fehler";
		};
	}

}
