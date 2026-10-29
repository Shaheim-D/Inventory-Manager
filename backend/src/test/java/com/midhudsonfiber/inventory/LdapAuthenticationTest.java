package com.midhudsonfiber.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LDAP / Active Directory sign-in, against a real LDAP server.
 *
 * <p>An in-memory directory on a loopback port, not a mock. A mocked
 * {@code DirContext} would prove that the code calls the methods the test
 * expects, which is the one thing never in doubt; what is in doubt is whether a
 * real bind, a real subtree search and a real {@code memberOf} behave the way
 * this assumes. The RADIUS tests make the same choice for the same reason.
 *
 * <p>The important tests here are the refusals. An LDAP authentication
 * implementation fails open in one famous way — an empty password is an
 * <em>anonymous</em> bind, which the server answers with success — so
 * {@code emptyPasswordIsRefused} is the one that matters most, and it is
 * asserted against a server that would genuinely accept the anonymous bind.
 */
class LdapAuthenticationTest extends AbstractIntegrationTest {

    private static final String BASE = "dc=corp,dc=example,dc=com";

    @Autowired
    private JdbcTemplate jdbc;

    private InMemoryDirectoryServer directory;
    private int port;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    @BeforeEach
    void startDirectory() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
        // No credential means anonymous binds are allowed -- which is exactly
        // the condition the empty-password test needs to be meaningful.
        config.setSchema(null);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test", 0));
        config.addAdditionalBindCredentials("cn=svc,ou=service," + BASE, "svc-password");

        directory = new InMemoryDirectoryServer(config);
        directory.startListening();
        port = directory.getListenPort();

        directory.add("dn: " + BASE, "objectClass: top", "objectClass: domain", "dc: corp");
        directory.add("dn: ou=people," + BASE, "objectClass: top", "objectClass: organizationalUnit",
                "ou: people");
        directory.add("dn: ou=groups," + BASE, "objectClass: top", "objectClass: organizationalUnit",
                "ou: groups");

        directory.add("dn: cn=Dana Engineer,ou=people," + BASE,
                "objectClass: top", "objectClass: person", "objectClass: inetOrgPerson",
                "cn: Dana Engineer", "sn: Engineer",
                "sAMAccountName: dana",
                "mail: dana@corp.example.com",
                "userPassword: DirectoryPass123",
                "memberOf: cn=IT Staff,ou=groups," + BASE,
                "memberOf: cn=All Employees,ou=groups," + BASE);

        directory.add("dn: cn=Sam Nobody,ou=people," + BASE,
                "objectClass: top", "objectClass: person", "objectClass: inetOrgPerson",
                "cn: Sam Nobody", "sn: Nobody",
                "sAMAccountName: sam",
                "userPassword: DirectoryPass123",
                "memberOf: cn=All Employees,ou=groups," + BASE);

        configure(true);
    }

    @AfterEach
    void stopDirectory() {
        if (directory != null) directory.shutDown(true);
        // The suite shares one database, so LDAP must not stay switched on for
        // everything that runs afterwards.
        jdbc.update("UPDATE ldap_settings SET is_enabled = false WHERE id = 1");
        jdbc.update("DELETE FROM ldap_role_mapping");
        jdbc.update("DELETE FROM user_role WHERE user_id IN "
                    + "(SELECT id FROM app_user WHERE auth_provider = 'LDAP')");
        jdbc.update("DELETE FROM app_user WHERE auth_provider = 'LDAP'");
    }

    /** Points the application at the in-memory server, service-account style. */
    private void configure(boolean enabled) {
        jdbc.update("""
                UPDATE ldap_settings
                   SET is_enabled = ?, host = 'localhost', port = ?, transport = 'NONE',
                       user_search_base = ?, user_search_filter = '(sAMAccountName={0})',
                       group_attribute = 'memberOf', upn_suffix = NULL,
                       bind_dn = ?, connect_timeout_seconds = 5
                 WHERE id = 1
                """, enabled, port, BASE, "cn=svc,ou=service," + BASE);
        // The service account password goes through the API so it is encrypted
        // by the same SecretCipher the provider decrypts with.
        put(admin(), "/api/admin/ldap-settings", """
                {"enabled":%s,"host":"localhost","port":%d,"transport":"NONE",
                 "userSearchBase":"%s","userSearchFilter":"(sAMAccountName={0})",
                 "groupAttribute":"memberOf","bindDn":"cn=svc,ou=service,%s",
                 "bindPassword":"svc-password","connectTimeoutSeconds":5}
                """.formatted(enabled, port, BASE, BASE));
    }

    private void mapGroupToRole(String groupValue, String roleName) {
        Long roleId = jdbc.queryForObject("SELECT id FROM role WHERE name = ?", Long.class, roleName);
        post(admin(), "/api/admin/ldap-settings/role-mappings", """
                {"groupValue":"%s","roleId":%d}
                """.formatted(groupValue, roleId));
    }

    @Test
    @DisplayName("a directory account signs in and is created locally on first use")
    void directoryAccountSignsIn() {
        assertThat(signInStatus("dana", "DirectoryPass123")).isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject(
                "SELECT auth_provider FROM app_user WHERE username = 'dana'", String.class))
                .isEqualTo("LDAP");
        // No password is ever stored for a directory account.
        assertThat(jdbc.queryForObject(
                "SELECT password_hash FROM app_user WHERE username = 'dana'", String.class))
                .isNull();
    }

    @Test
    @DisplayName("AN EMPTY PASSWORD IS REFUSED, even though the server would accept the bind")
    void emptyPasswordIsRefused() {
        // The in-memory server allows anonymous binds, so a naive implementation
        // signs in as anybody with a blank password. This is the single most
        // common way to build an LDAP authentication bypass.
        assertThat(signInStatus("dana", "")).isNotEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE username = 'dana'", Integer.class))
                .as("an anonymous bind must never provision an account").isZero();
    }

    @Test
    @DisplayName("a wrong password is refused and no account is created")
    void wrongPasswordIsRefused() {
        assertThat(signInStatus("dana", "NotThePassword")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE username = 'dana'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a username that is not in the directory is refused")
    void unknownUserIsRefused() {
        assertThat(signInStatus("nosuchperson", "DirectoryPass123"))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a mapped directory group grants its role at sign-in")
    void groupMembershipGrantsRole() {
        mapGroupToRole("IT Staff", "Network Engineer");

        assertThat(signInStatus("dana", "DirectoryPass123")).isEqualTo(HttpStatus.OK);

        // Mapped on the CN alone, while memberOf carries the full DN -- an
        // operator should not have to transcribe a DN to grant a role.
        assertThat(jdbc.queryForObject("""
                SELECT r.name FROM app_user u
                  JOIN user_role ur ON ur.user_id = u.id
                  JOIN role r ON r.id = ur.role_id
                 WHERE u.username = 'dana'
                """, String.class)).isEqualTo("Network Engineer");
    }

    @Test
    @DisplayName("the full DN works as a mapping too")
    void fullDistinguishedNameAlsoMaps() {
        mapGroupToRole("cn=IT Staff,ou=groups," + BASE, "Asset Manager");

        assertThat(signInStatus("dana", "DirectoryPass123")).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("""
                SELECT r.name FROM app_user u
                  JOIN user_role ur ON ur.user_id = u.id
                  JOIN role r ON r.id = ur.role_id
                 WHERE u.username = 'dana'
                """, String.class)).isEqualTo("Asset Manager");
    }

    @Test
    @DisplayName("somebody in no mapped group lands in Unassigned, not in nothing")
    void unmappedUserGetsTheReadOnlyFloor() {
        mapGroupToRole("IT Staff", "Network Engineer");

        assertThat(signInStatus("sam", "DirectoryPass123")).isEqualTo(HttpStatus.OK);
        // Sam is only in All Employees, which nobody mapped. A real employee who
        // has just signed in should see a read-only view, not a wall.
        assertThat(jdbc.queryForObject("""
                SELECT r.name FROM app_user u
                  JOIN user_role ur ON ur.user_id = u.id
                  JOIN role r ON r.id = ur.role_id
                 WHERE u.username = 'sam'
                """, String.class)).isEqualTo("Unassigned");
    }

    @Test
    @DisplayName("removing somebody from the group removes the access at their next sign-in")
    void rolesAreReplacedNotAccumulated() throws Exception {
        mapGroupToRole("IT Staff", "Network Engineer");
        assertThat(signInStatus("dana", "DirectoryPass123")).isEqualTo(HttpStatus.OK);

        // Take the group away in the directory, as an administrator would.
        directory.modify("dn: cn=Dana Engineer,ou=people," + BASE,
                "changetype: modify", "delete: memberOf",
                "memberOf: cn=IT Staff,ou=groups," + BASE);

        assertThat(signInStatus("dana", "DirectoryPass123")).isEqualTo(HttpStatus.OK);

        // Directory-driven access that only ever grants is an accumulation, not
        // access control.
        assertThat(jdbc.queryForObject("""
                SELECT r.name FROM app_user u
                  JOIN user_role ur ON ur.user_id = u.id
                  JOIN role r ON r.id = ur.role_id
                 WHERE u.username = 'dana'
                """, String.class)).isEqualTo("Unassigned");
    }

    @Test
    @DisplayName("a local administrator's roles are never touched by the directory")
    void localAccountsAreUnaffected() {
        mapGroupToRole("IT Staff", "Network Engineer");

        // The bootstrap admin is LOCAL and exists in no directory. Its roles
        // must survive anything LDAP says or fails to say -- it is the account
        // somebody needs when the directory is the problem.
        assertThat(signInStatus("admin", "BootstrapAdmin123")).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app_user u
                  JOIN user_role ur ON ur.user_id = u.id
                  JOIN role r ON r.id = ur.role_id
                 WHERE u.username = 'admin' AND r.name = 'Administrator'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a disabled account cannot sign in even though the directory accepts it")
    void disabledAccountsStayDisabled() {
        assertThat(signInStatus("dana", "DirectoryPass123")).isEqualTo(HttpStatus.OK);
        jdbc.update("UPDATE app_user SET is_active = false WHERE username = 'dana'");

        // The directory knows nothing about this application's idea of a
        // disabled user, so the check has to hold on this side.
        assertThat(signInStatus("dana", "DirectoryPass123")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("with LDAP switched off nothing reaches the directory")
    void disabledMeansDisabled() {
        configure(false);
        assertThat(signInStatus("dana", "DirectoryPass123")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE username = 'dana'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("an unreachable directory is reported as unreachable, not as a bad password")
    void unreachableIsNotARejection() {
        directory.shutDown(true);   // the host is now refusing connections

        HttpStatusCode status = signInStatus("dana", "DirectoryPass123");

        // 503, not 401. Telling somebody their password is wrong when the
        // directory is down sends them to reset a password that was fine.
        assertThat(status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("the test button reports the groups, which is how mappings get typed correctly")
    void testEndpointReportsGroups() {
        mapGroupToRole("IT Staff", "Network Engineer");

        ResponseEntity<JsonNode> response = post(admin(), "/api/admin/ldap-settings/test", """
                {"username":"dana","password":"DirectoryPass123"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("groups").toString()).contains("IT Staff", "All Employees");
        // The preview closes the loop: it says which roles those groups grant,
        // so an operator can see the mapping work before switching sign-in on.
        assertThat(body.get("mappedRoles").toString()).contains("Network Engineer");
    }

    @Test
    @DisplayName("the service account password is stored encrypted and never returned")
    void servicePasswordIsNeverReadable() {
        String stored = jdbc.queryForObject(
                "SELECT bind_password_enc FROM ldap_settings WHERE id = 1", String.class);
        assertThat(stored).isNotNull().doesNotContain("svc-password");

        JsonNode body = get(admin(), "/api/admin/ldap-settings").getBody();
        assertThat(body.get("bindPasswordSet").asBoolean()).isTrue();
        assertThat(body.has("bindPassword")).isFalse();
        assertThat(body.has("bindPasswordEnc")).isFalse();
        assertThat(body.toString()).doesNotContain("svc-password");
    }

    @Test
    @DisplayName("sign-in leaves the password fields of the account alone")
    void signInDoesNotTouchCredentialColumns() {
        assertThat(signInStatus("dana", "DirectoryPass123")).isEqualTo(HttpStatus.OK);

        // The same guarantee PluginFrameworkIntegrationTest asserts for directory
        // sync: authentication may set roles and nothing else about credentials.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app_user
                 WHERE username = 'dana'
                   AND password_hash IS NULL
                   AND locked_until IS NULL
                   AND failed_login_attempts = 0
                """, Integer.class)).isEqualTo(1);
    }
}
