package dev.homeserver.smarthome;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;

/**
 * Does in the tests what the app will do with Kratos: redeem a setup code, choose a password, sign in, and look at
 * identities through the admin API.
 */
public final class KratosTestClient {

	private final String publicUrl;
	private final String adminUrl;

	public KratosTestClient(String publicUrl, String adminUrl) {
		this.publicUrl = publicUrl;
		this.adminUrl = adminUrl;
	}

	/**
	 * Redeems a setup code and sets the password. The code belongs to a browser flow, so this keeps cookies like a
	 * browser (or the app's HTTP client) would.
	 */
	public void redeemSetupCode(String link, String code, String password) throws Exception {
		HttpClient browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
		String flow = link.substring(link.indexOf("flow=") + 5);
		var recovery = send(browser, "POST", publicUrl + "/self-service/recovery?flow=" + flow,
				"{\"method\": \"code\", \"code\": \"" + code + "\"}");
		// Kratos answers 422 "browser location change required" and points to the settings flow
		String redirect = JsonPath.read(recovery.body(), "$.redirect_browser_to");
		String settingsFlow = redirect.substring(redirect.indexOf("flow=") + 5);
		var settings = send(browser, "GET", publicUrl + "/self-service/settings/flows?id=" + settingsFlow, null);
		List<String> csrf = JsonPath.read(settings.body(), "$.ui.nodes[?(@.attributes.name == 'csrf_token')].attributes.value");
		var result = send(browser, "POST", publicUrl + "/self-service/settings?flow=" + settingsFlow,
				"{\"method\": \"password\", \"password\": \"" + password + "\", \"csrf_token\": \"" + csrf.getFirst() + "\"}");
		if (result.statusCode() != 200) {
			throw new IllegalStateException("Setting the password failed: " + result.body());
		}
	}

	/** Signs in like the app (API flow); true if Kratos hands out a session token. */
	public boolean signIn(String identifier, String password) throws Exception {
		HttpClient app = HttpClient.newHttpClient();
		String flow = JsonPath.read(send(app, "GET", publicUrl + "/self-service/login/api", null).body(), "$.id");
		var login = send(app, "POST", publicUrl + "/self-service/login?flow=" + flow,
				"{\"method\": \"password\", \"identifier\": \"" + identifier + "\", \"password\": \"" + password + "\"}");
		return login.statusCode() == 200 && login.body().contains("session_token");
	}

	/** The identity from the admin API, or null if it does not exist. */
	public Map<String, Object> identity(String identityId) throws Exception {
		var response = send(HttpClient.newHttpClient(), "GET", adminUrl + "/admin/identities/" + identityId, null);
		return response.statusCode() == 404 ? null : JsonPath.read(response.body(), "$");
	}

	private static HttpResponse<String> send(HttpClient client, String method, String url, String json)
			throws IOException, InterruptedException {
		var request = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json");
		if (json != null) {
			request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
		}
		else {
			request.method(method, HttpRequest.BodyPublishers.noBody());
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

}
