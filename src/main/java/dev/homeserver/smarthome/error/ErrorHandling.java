package dev.homeserver.smarthome.error;

import java.util.List;
import java.util.Map;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Gives Spring's own errors (validation, unreadable JSON, wrong parameter types, unknown paths) the same form as
 * {@link ApiException}: German title and detail plus a {@code code}. Validation errors list the fields in
 * {@code errors}.
 */
@RestControllerAdvice
public class ErrorHandling extends ResponseEntityExceptionHandler {

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		if (body instanceof ProblemDetail problem && !(ex instanceof ApiException)) {
			problem.setTitle(Titles.of(status));
			var german = germanDetail(ex);
			if (german != null) {
				problem.setDetail(german.detail());
				problem.setProperty("code", german.code());
			}
			else if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
				problem.setProperty("code", "http." + status.value());
			}
		}
		return super.handleExceptionInternal(ex, body, headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ex.getBody();
		problem.setTitle(Titles.of(status));
		problem.setDetail("Die Eingaben sind unvollständig oder ungültig.");
		problem.setProperty("code", "validation");
		List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(e -> Map.of("field", e.getField(), "message", germanConstraint(e.getCode(), e.getDefaultMessage())))
				.toList();
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	/** German text per constraint, independent of the validator's language. */
	private static String germanConstraint(String constraint, String fallback) {
		return switch (constraint == null ? "" : constraint) {
			case "NotNull" -> "darf nicht fehlen";
			case "NotBlank", "NotEmpty" -> "darf nicht leer sein";
			case "Size" -> "hat die falsche Länge";
			case "Email" -> "ist keine gültige E-Mail-Adresse";
			case "Min", "Max", "Positive", "PositiveOrZero" -> "liegt außerhalb des erlaubten Bereichs";
			case "Pattern" -> "hat das falsche Format";
			default -> String.valueOf(fallback);
		};
	}

	private record German(String code, String detail) {
	}

	private static German germanDetail(Exception ex) {
		return switch (ex) {
			case HttpMessageNotReadableException e -> new German("request.unreadable",
					"Die Anfrage konnte nicht gelesen werden (ungültiges JSON oder falscher Typ).");
			case TypeMismatchException e -> new German("request.parameter_invalid",
					"Der Wert von „" + e.getPropertyName() + "“ ist ungültig.");
			case MissingServletRequestParameterException e -> new German("request.parameter_missing",
					"Der Parameter „" + e.getParameterName() + "“ fehlt.");
			case HandlerMethodValidationException e -> new German("validation", "Die Eingaben sind ungültig.");
			case HttpRequestMethodNotSupportedException e -> new German("request.method_not_allowed",
					"Diese Methode ist hier nicht erlaubt.");
			case HttpMediaTypeNotSupportedException e -> new German("request.media_type",
					"Dieses Format wird nicht unterstützt, bitte JSON schicken.");
			case NoResourceFoundException e -> new German("not_found", "Diese Adresse gibt es nicht.");
			default -> null;
		};
	}

}
