package dev.homeserver.smarthome.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

import com.jayway.jsonpath.JsonPath;

import dev.homeserver.smarthome.KratosTestClient;
import dev.homeserver.smarthome.TestJwts;
import dev.homeserver.smarthome.TestcontainersConfiguration;
import dev.homeserver.smarthome.token.ApiTokenService;

/** Users and roles: admins create accounts in Kratos, hand out roles, and can never remove the last admin. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserAdminTest {

	private static final String ADMIN = TestJwts.token(UUID.randomUUID(), "admin@example.com", "Admin");
	private static final String RESIDENT = TestJwts.token(UUID.randomUUID(), "bewohner@example.com", "Erika");

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	RoleRepository roles;

	@Autowired
	ApiTokenService tokens;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	GenericContainer<?> kratosContainer;

	KratosTestClient kratos;

	@BeforeEach
	void kratosClient() {
		kratos = new KratosTestClient(TestcontainersConfiguration.kratosPublicUrl(kratosContainer),
				TestcontainersConfiguration.kratosAdminUrl(kratosContainer));
	}

	@Test
	void adminCreatesAccountThatIsSetUpWithTheCode() throws Exception {
		String username = "user" + suffix();
		String created = createUser(username, "  Max Mustermann ", "bewohner")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.user.name").value("Max Mustermann"))
				.andExpect(jsonPath("$.user.username").value(username))
				.andExpect(jsonPath("$.user.email").value(username + "@example.com"))
				.andExpect(jsonPath("$.user.roles", contains("bewohner")))
				.andExpect(jsonPath("$.user.machine").value(false))
				.andExpect(jsonPath("$.setupCode.code", matchesPattern("\\d{6}")))
				.andExpect(jsonPath("$.setupCode.expiresAt").exists())
				.andReturn().getResponse().getContentAsString();
		int id = JsonPath.read(created, "$.user.id");

		// The person enters the code in the app, chooses a password and can sign in
		kratos.redeemSetupCode(JsonPath.read(created, "$.setupCode.link"), JsonPath.read(created, "$.setupCode.code"),
				"ein-langes-passwort-42");
		assertThat(kratos.signIn(username, "ein-langes-passwort-42")).isTrue();

		// Their JWT maps to the user the admin created, with its roles
		UUID identity = users.findById((long) id).orElseThrow().getIdentityId();
		as(jwt(identity, username, "Max Mustermann"), get("/api/me"))
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.roles", contains("bewohner")));

		as(ADMIN, get("/api/users")).andExpect(jsonPath("$[*].username", hasItem(username)));
		as(ADMIN, get("/api/users/" + id))
				.andExpect(jsonPath("$.user.id").value(id))
				.andExpect(jsonPath("$.grants").isArray());
		as(ADMIN, post("/api/users/" + id + "/setup-code"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code", matchesPattern("\\d{6}")));
	}

	@Test
	void refusesTakenAndInvalidAccounts() throws Exception {
		String username = "dup" + suffix();
		createUser(username, "Erster", "gast").andExpect(status().isCreated());

		createUser(username, "Zweiter", "gast").andExpect(status().isConflict());
		createUser("Gross" + suffix(), "Großbuchstaben", "gast").andExpect(status().isBadRequest());
		createUser("ok" + suffix(), "Unbekannte Rolle", "chef").andExpect(status().isBadRequest());
		createUser("ok" + suffix(), "   ", "gast").andExpect(status().isBadRequest());
		as(ADMIN, get("/api/users/999999")).andExpect(status().isNotFound());
	}

	@Test
	void onlyAdminsManageUsersRolesAndGrants() throws Exception {
		String ai = aiToken();
		for (String bearer : List.of(RESIDENT, ai)) {
			as(bearer, get("/api/users")).andExpect(status().isForbidden());
			as(bearer, post("/api/users").contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\": \"x@example.com\", \"username\": \"xxx\", \"name\": \"X\"}"))
					.andExpect(status().isForbidden());
			as(bearer, get("/api/roles")).andExpect(status().isForbidden());
			as(bearer, get("/api/grants")).andExpect(status().isForbidden());
			as(bearer, get("/api/users/1/effective-permissions")).andExpect(status().isForbidden());
		}
		mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
	}

	@Test
	void renamesDeactivatesAndDeletesInKratosToo() throws Exception {
		String username = "life" + suffix();
		String created = createUser(username, "Lebenszyklus", "bewohner").andReturn().getResponse().getContentAsString();
		int id = JsonPath.read(created, "$.user.id");
		String identity = users.findById((long) id).orElseThrow().getIdentityId().toString();
		String jwt = jwt(UUID.fromString(identity), username, "Neuer Name");

		as(ADMIN, patch("/api/users/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Neuer Name\"}"))
				.andExpect(jsonPath("$.name").value("Neuer Name"));
		assertThat(kratos.identity(identity)).extractingByKey("traits").asString().contains("Neuer Name");

		as(ADMIN, patch("/api/users/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
				.andExpect(jsonPath("$.active").value(false));
		assertThat(kratos.identity(identity)).containsEntry("state", "inactive");
		as(jwt, get("/api/me")).andExpect(status().isUnauthorized());
		as(ADMIN, post("/api/users/" + id + "/setup-code")).andExpect(status().isConflict());

		as(ADMIN, patch("/api/users/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"active\": true}"))
				.andExpect(jsonPath("$.active").value(true));
		assertThat(kratos.identity(identity)).containsEntry("state", "active");
		as(jwt, get("/api/me")).andExpect(status().isOk());

		as(ADMIN, put("/api/users/" + id + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"roles\": [\"gast\", \"bewohner\"]}"))
				.andExpect(jsonPath("$.roles", contains("bewohner", "gast")));

		as(ADMIN, delete("/api/users/" + id)).andExpect(status().isNoContent());
		as(ADMIN, get("/api/users/" + id)).andExpect(status().isNotFound());
		assertThat(kratos.identity(identity)).isNull();
	}

	@Test
	void aiCannotBeDeletedOrMadeAdmin() throws Exception {
		long ki = kiUserId();
		as(ADMIN, delete("/api/users/" + ki)).andExpect(status().isConflict());
		as(ADMIN, put("/api/users/" + ki + "/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"roles\": [\"ki\", \"admin\"]}"))
				.andExpect(status().isBadRequest());
		as(ADMIN, post("/api/users/" + ki + "/setup-code")).andExpect(status().isConflict());
		as(ADMIN, get("/api/users/" + ki))
				.andExpect(jsonPath("$.user.machine").value(true))
				.andExpect(jsonPath("$.user.roles", contains("ki")));
	}

	@Test
	void lastActiveAdminCannotBeRemoved() throws Exception {
		String lastAdmin = TestJwts.token(UUID.randomUUID(), "admin@example.com", "Letzter Admin");
		long me = ((Number) JsonPath.read(as(lastAdmin, get("/api/me")).andReturn().getResponse().getContentAsString(),
				"$.id")).longValue();
		// Other tests sign in as admin too; take the role away from everyone else for this test.
		// The initial admin gets it back on their next request.
		List<Long> others = removeAdminRoleFromAllBut(me);
		try {
			as(lastAdmin, put("/api/users/" + me + "/roles").contentType(MediaType.APPLICATION_JSON)
					.content("{\"roles\": []}"))
					.andExpect(status().isConflict());
			as(lastAdmin, patch("/api/users/" + me).contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
					.andExpect(status().isConflict());
			as(lastAdmin, delete("/api/users/" + me)).andExpect(status().isConflict());

			// With a second admin it works
			String second = createUser("admin" + suffix(), "Zweiter Admin", "admin").andReturn().getResponse()
					.getContentAsString();
			int secondId = JsonPath.read(second, "$.user.id");
			as(lastAdmin, put("/api/users/" + me + "/roles").contentType(MediaType.APPLICATION_JSON)
					.content("{\"roles\": [\"bewohner\"]}"))
					.andExpect(status().isOk());
			as(ADMIN, put("/api/users/" + secondId + "/roles").contentType(MediaType.APPLICATION_JSON)
					.content("{\"roles\": []}"))
					.andExpect(status().isOk());
		}
		finally {
			restoreAdminRole(others);
		}
	}

	@Test
	void adminsAddTheirOwnRolesButCannotDeleteSystemRoles() throws Exception {
		as(ADMIN, get("/api/roles"))
				.andExpect(jsonPath("$[?(@.system == true)].name", containsInAnyOrder("admin", "bewohner", "gast", "ki")));

		String name = "nachbar" + suffix();
		String created = as(ADMIN, post("/api/roles").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"" + name + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.system").value(false))
				.andReturn().getResponse().getContentAsString();
		int roleId = JsonPath.read(created, "$.id");
		as(ADMIN, post("/api/roles").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + name + "\"}"))
				.andExpect(status().isConflict());
		as(ADMIN, post("/api/roles").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Mit Leerzeichen\"}"))
				.andExpect(status().isBadRequest());
		long gast = roles.findByName("gast").orElseThrow().getId();
		as(ADMIN, delete("/api/roles/" + gast)).andExpect(status().isConflict());

		// A role that someone still has cannot be deleted
		String user = createUser("nb" + suffix(), "Nachbarin", name).andReturn().getResponse().getContentAsString();
		int userId = JsonPath.read(user, "$.user.id");
		as(ADMIN, delete("/api/roles/" + roleId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.userCount").value(1));
		as(ADMIN, put("/api/users/" + userId + "/roles").contentType(MediaType.APPLICATION_JSON).content("{\"roles\": []}"))
				.andExpect(status().isOk());
		as(ADMIN, delete("/api/roles/" + roleId)).andExpect(status().isNoContent());
		as(ADMIN, get("/api/roles")).andExpect(jsonPath("$[*].name", not(hasItem(name))));
	}

	/** A JWT with the claims the Kratos tokenizer sets for an account created as {@code username}@example.com. */
	private static String jwt(UUID identity, String username, String name) {
		return TestJwts.sign(TestJwts.claims(identity,
				Map.of("email", username + "@example.com", "name", name, "preferred_username", username)).build());
	}

	private ResultActions createUser(String username, String name, String role) throws Exception {
		return as(ADMIN, post("/api/users").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\": \"" + username + "@example.com\", \"username\": \"" + username + "\", \"name\": \""
						+ name + "\", \"roles\": [\"" + role + "\"]}"));
	}

	private List<Long> removeAdminRoleFromAllBut(long keep) {
		return tx.execute(status -> {
			List<Long> changed = new ArrayList<>();
			for (User user : users.findAll()) {
				if (user.getId() != keep && user.hasRole(Role.ADMIN)) {
					user.getRoles().removeIf(r -> r.getName().equals(Role.ADMIN));
					changed.add(user.getId());
				}
			}
			return changed;
		});
	}

	private void restoreAdminRole(List<Long> userIds) {
		tx.executeWithoutResult(status -> {
			Role admin = roles.findByName(Role.ADMIN).orElseThrow();
			users.findAllById(userIds).forEach(user -> user.getRoles().add(admin));
		});
	}

	private long kiUserId() {
		return users.findAll().stream().filter(u -> u.isMachine() && u.hasRole(Role.KI)).findFirst().orElseThrow().getId();
	}

	private String aiToken() {
		return tokens.create(kiUserId(), "user admin test").secret();
	}

	private static String suffix() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private ResultActions as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
	}

}
