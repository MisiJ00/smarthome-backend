package dev.homeserver.smarthome.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.homeserver.smarthome.TestJwts;
import dev.homeserver.smarthome.TestcontainersConfiguration;

/** Requests with Kratos JWTs: who gets in, which user is created and which role it gets. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Test
	void rejectsRequestsWithoutValidToken() throws Exception {
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/devices")).andExpect(status().isUnauthorized());
		// The old placeholder login admin/admin is gone
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46YWRtaW4="))
				.andExpect(status().isUnauthorized());

		UUID identity = UUID.randomUUID();
		Instant past = Instant.now().minusSeconds(600);
		var expired = TestJwts.claims(identity, Map.of())
				.issueTime(Date.from(past)).notBeforeTime(Date.from(past)).expirationTime(Date.from(past.plusSeconds(60)))
				.build();
		as(TestJwts.sign(expired), get("/api/me")).andExpect(status().isUnauthorized());
		as(TestJwts.signWithForeignKey(TestJwts.claims(identity, Map.of()).build()), get("/api/me"))
				.andExpect(status().isUnauthorized());
		as(TestJwts.sign(TestJwts.claims(identity, Map.of()).issuer("http://elsewhere/").build()), get("/api/me"))
				.andExpect(status().isUnauthorized());
		as(TestJwts.sign(TestJwts.claims(identity, Map.of()).subject("not-a-uuid").build()), get("/api/me"))
				.andExpect(status().isUnauthorized());

		assertThat(users.findByIdentityId(identity)).isEmpty();
	}

	@Test
	void createsUserOnFirstRequestAndKeepsNameInSync() throws Exception {
		UUID identity = UUID.randomUUID();

		as(TestJwts.token(identity, "bewohner@example.com", "Erika"), get("/api/me"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.identityId").value(identity.toString()))
				.andExpect(jsonPath("$.name").value("Erika"))
				.andExpect(jsonPath("$.email").value("bewohner@example.com"))
				.andExpect(jsonPath("$.roles", empty()));
		long id = users.findByIdentityId(identity).orElseThrow().getId();

		// Second request: same user, name changed in Kratos
		as(TestJwts.token(identity, "bewohner@example.com", "Erika M."), get("/api/me"))
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.name").value("Erika M."));

		// Without the admin role the device API is closed for now
		as(TestJwts.token(identity, "bewohner@example.com", "Erika"), get("/api/devices")).andExpect(status().isForbidden());
	}

	@Test
	void initialAdminFromConfigurationGetsAdminRole() throws Exception {
		String token = TestJwts.token(UUID.randomUUID(), "Admin@Example.com", "Mustafa");

		as(token, get("/api/me")).andExpect(jsonPath("$.roles", contains("admin")));
		as(token, get("/api/devices")).andExpect(status().isOk());
	}

	private ResultActions as(String jwt, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt));
	}

}
