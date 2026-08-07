package com.midhudsonfiber.inventory;

import com.midhudsonfiber.inventory.security.SecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RADIUS sign-in, and the rule that matters most about it: <b>it is in addition
 * to local sign-in, never instead of it.</b>
 *
 * <p>Runs against a real RADIUS server on a loopback UDP port, implementing just
 * enough of RFC 2865 to accept one credential and reject everything else. A
 * mocked client would not exercise the shared-secret handling, the packet
 * encoding, or the timeout path -- which are the three things actually likely to
 * be wrong.
 */
class RadiusAuthenticationTest extends AbstractIntegrationTest {

    private static final String SHARED_SECRET = "test-shared-secret";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SecretCipher cipher;

    private final List<FakeRadiusServer> started = new ArrayList<>();

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    @AfterEach
    void tearDown() {
        started.forEach(FakeRadiusServer::stop);
        started.clear();
        // Leave RADIUS off: these tests share a database with every other test,
        // and a stray enabled row would send their sign-ins at a dead port.
        jdbc.update("DELETE FROM radius_server");
        jdbc.update("UPDATE radius_settings SET is_enabled = false WHERE id = 1");
    }

    /** A running server that accepts one credential, registered at this ordinal. */
    private FakeRadiusServer radiusAccepting(int ordinal, String username, String password) throws Exception {
        FakeRadiusServer server = new FakeRadiusServer(username, password);
        server.start();
        started.add(server);
        addServer(ordinal, server.port());
        return server;
    }

    /** A server row pointing at a port, with the shared secret stored encrypted. */
    private void addServer(int ordinal, int port) {
        jdbc.update("INSERT INTO radius_server (ordinal, host, port, shared_secret_enc) VALUES (?, '127.0.0.1', ?, ?)",
                ordinal, port, cipher.encrypt(SHARED_SECRET));
        enable();
    }

    private void enable() {
        jdbc.update("UPDATE radius_settings SET is_enabled = true, timeout_seconds = 2, retries = 1 WHERE id = 1");
    }

    private void radiusAccepts(String username, String password) throws Exception {
        radiusAccepting(1, username, password);
    }

    // -----------------------------------------------------------------
    // The fallback, in both directions
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a local password and a RADIUS password both sign the same person in")
    void eitherCredentialWorks() throws Exception {
        Session admin = admin();
        String username = unique("dual-user");

        post(admin, "/api/admin/users", """
                {"username":"%s","password":"LocalPassword123","roleIds":[]}
                """.formatted(username));

        radiusAccepts(username, "NetworkPassword456");

        // The local password: answered by the local provider, which is first.
        assertThat(signInStatus(username, "LocalPassword123"))
                .as("the password set in this application still works")
                .isEqualTo(HttpStatus.OK);

        // The network password: the local provider rejects it, and the chain
        // carries on to RADIUS rather than stopping at the first refusal.
        assertThat(signInStatus(username, "NetworkPassword456"))
                .as("the network password works for the same account")
                .isEqualTo(HttpStatus.OK);

        // And something that is neither is still refused.
        assertThat(signInStatus(username, "NeitherOfThem789"))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("signing in with a RADIUS password does not count as a failed local attempt")
    void radiusSuccessDoesNotAccumulateLockout() throws Exception {
        Session admin = admin();
        String username = unique("no-lockout");

        post(admin, "/api/admin/users", """
                {"username":"%s","password":"LocalPassword123","roleIds":[]}
                """.formatted(username));
        radiusAccepts(username, "NetworkPassword456");

        // Lockout is five consecutive failures. Every one of these fails against
        // the local provider before RADIUS accepts it, so counting per-provider
        // would lock the account on the fifth -- while the person was signing in
        // successfully every single time.
        for (int i = 0; i < 6; i++) {
            assertThat(signInStatus(username, "NetworkPassword456"))
                    .as("sign-in %d of 6 with the network password", i + 1)
                    .isEqualTo(HttpStatus.OK);
        }

        Long userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, username);
        assertThat(jdbc.queryForObject(
                "SELECT failed_login_attempts FROM app_user WHERE id = ?", Integer.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT locked_until IS NULL FROM app_user WHERE id = ?", Boolean.class, userId))
                .as("a working sign-in must never leave the account locked").isTrue();
    }

    @Test
    @DisplayName("an unreachable RADIUS server does not stop local sign-in, and is not a wrong password")
    void unreachableServerLeavesLocalSignInAlone() {
        Session admin = admin();
        String username = unique("radius-down");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"LocalPassword123","roleIds":[]}
                """.formatted(username));

        // Enabled, pointed at a port with nothing behind it.
        addServer(1, 1);
        jdbc.update("UPDATE radius_settings SET timeout_seconds = 1, retries = 1 WHERE id = 1");

        assertThat(signInStatus(username, "LocalPassword123"))
                .as("the local provider answers first, so RADIUS being down is irrelevant")
                .isEqualTo(HttpStatus.OK);

        // For a credential the local provider cannot answer, the outage has to be
        // reported as an outage. 401 would send somebody to reset a password that
        // was never wrong.
        assertThat(signInStatus(username, "SomeNetworkPassword"))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // And it must not have been counted against the account, or an NPS
        // outage would lock out everyone who tried during it.
        Long userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, username);
        assertThat(jdbc.queryForObject(
                "SELECT failed_login_attempts FROM app_user WHERE id = ?", Integer.class, userId))
                .as("an unreachable server is not a failed attempt").isZero();
    }

    @Test
    @DisplayName("a first-time RADIUS user is provisioned with no permissions")
    void firstTimeUserArrivesUnassigned() throws Exception {
        String username = unique("newcomer");
        radiusAccepts(username, "NetworkPassword456");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE username = ?", Integer.class, username))
                .as("nobody has typed this account in").isZero();

        assertThat(signInStatus(username, "NetworkPassword456")).isEqualTo(HttpStatus.OK);

        Long userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, username);
        assertThat(userId).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT auth_provider FROM app_user WHERE id = ?", String.class, userId))
                .isEqualTo("RADIUS");
        assertThat(jdbc.queryForObject(
                "SELECT password_hash IS NULL FROM app_user WHERE id = ?", Boolean.class, userId))
                .as("no local password is invented for them").isTrue();

        // Unassigned holds no permissions, so the account exists and can do
        // nothing until somebody decides what it should be.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM user_role ur JOIN role r ON r.id = ur.role_id
                 WHERE ur.user_id = ? AND r.name = 'Unassigned'
                """, Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM user_role ur
                  JOIN role_permission rp ON rp.role_id = ur.role_id
                 WHERE ur.user_id = ?
                """, Integer.class, userId)).isZero();
    }

    @Test
    @DisplayName("a disabled account stays out even when RADIUS accepts the password")
    void disabledAccountIsStillRefused() throws Exception {
        Session admin = admin();
        String username = unique("disabled-user");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"LocalPassword123","roleIds":[]}
                """.formatted(username));
        jdbc.update("UPDATE app_user SET is_active = false WHERE username = ?", username);

        radiusAccepts(username, "NetworkPassword456");

        assertThat(signInStatus(username, "NetworkPassword456"))
                .as("NPS knows nothing about this application's idea of a disabled account")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------
    // Two servers
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the secondary answers when the primary does not")
    void failsOverToTheSecondary() throws Exception {
        Session admin = admin();
        String username = unique("failover");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"LocalPassword123","roleIds":[]}
                """.formatted(username));

        // Primary is a port with nothing behind it; secondary is real.
        addServer(1, 1);
        radiusAccepting(2, username, "NetworkPassword456");
        jdbc.update("UPDATE radius_settings SET timeout_seconds = 1, retries = 1 WHERE id = 1");

        assertThat(signInStatus(username, "NetworkPassword456"))
                .as("a dead primary must not stop the secondary answering")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a reject from the primary is authoritative and is not retried against the secondary")
    void rejectDoesNotFailOver() throws Exception {
        Session admin = admin();
        String username = unique("authoritative");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"LocalPassword123","roleIds":[]}
                """.formatted(username));

        FakeRadiusServer primary = radiusAccepting(1, username, "PrimaryKnowsThis");
        FakeRadiusServer secondary = radiusAccepting(2, username, "SecondaryKnowsThis");

        // The secondary would accept this. The primary answers first and says no,
        // and that answer stands -- otherwise one bad password becomes a failed
        // attempt on every server, which is how an account gets locked out in AD
        // by an application nobody suspects.
        assertThat(signInStatus(username, "SecondaryKnowsThis"))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(primary.requestCount()).as("the primary was asked").isPositive();
        assertThat(secondary.requestCount())
                .as("the secondary was never asked after an authoritative reject").isZero();

        // And the credential the primary does know still works.
        assertThat(signInStatus(username, "PrimaryKnowsThis")).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("both servers down is an outage, not a wrong password")
    void bothDownIsAnOutage() {
        Session admin = admin();
        String username = unique("both-down");
        post(admin, "/api/admin/users", """
                {"username":"%s","password":"LocalPassword123","roleIds":[]}
                """.formatted(username));

        addServer(1, 1);
        addServer(2, 2);
        jdbc.update("UPDATE radius_settings SET timeout_seconds = 1, retries = 1 WHERE id = 1");

        assertThat(signInStatus(username, "SomeNetworkPassword"))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(signInStatus(username, "LocalPassword123"))
                .as("local sign-in is untouched by both being down")
                .isEqualTo(HttpStatus.OK);
    }

    // -----------------------------------------------------------------
    // The secret
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the shared secret is never returned, and is not readable in the database")
    void secretIsNeverReturnedNorStoredInClear() throws Exception {
        Session admin = admin();
        radiusAccepting(1, unique("someone"), "irrelevant");

        var body = get(admin, "/api/admin/radius-settings").getBody();
        assertThat(body).isNotNull();
        assertThat(body.toString())
                .as("no endpoint hands the secret back, in any field")
                .doesNotContain(SHARED_SECRET);

        var server = body.get("servers").get(0);
        assertThat(server.get("secretSet").asBoolean()).as("only that one is set").isTrue();
        assertThat(server.get("secretReadable").asBoolean()).isTrue();
        assertThat(server.has("sharedSecret")).isFalse();
        assertThat(server.has("sharedSecretEnc")).isFalse();

        // And the column itself. This is the property that makes a leaked
        // pg_dump inert rather than a leaked shared secret.
        String stored = jdbc.queryForObject(
                "SELECT shared_secret_enc FROM radius_server WHERE ordinal = 1", String.class);
        assertThat(stored).isNotNull().doesNotContain(SHARED_SECRET);
        assertThat(cipher.decrypt(stored)).as("and it is genuinely the secret, encrypted")
                .isEqualTo(SHARED_SECRET);
    }

    @Test
    @DisplayName("the same secret encrypts differently every time")
    void encryptionIsNonDeterministic() {
        String first = cipher.encrypt(SHARED_SECRET);
        String second = cipher.encrypt(SHARED_SECRET);
        // A fresh nonce per encryption, so two servers sharing a secret do not
        // announce that fact to anyone reading the table.
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(SHARED_SECRET);
        assertThat(cipher.decrypt(second)).isEqualTo(SHARED_SECRET);
    }

    @Test
    @DisplayName("a secret that cannot be decrypted is reported rather than silently failing")
    void undecryptableSecretIsVisible() {
        Session admin = admin();
        // What a restore onto a host without the original key file looks like.
        jdbc.update("INSERT INTO radius_server (ordinal, host, port, shared_secret_enc) "
                + "VALUES (1, '127.0.0.1', 1812, 'bm90LWEtcmVhbC1jaXBoZXJ0ZXh0LWF0LWFsbA==')");

        var server = get(admin, "/api/admin/radius-settings").getBody().get("servers").get(0);
        assertThat(server.get("secretSet").asBoolean()).as("something is stored").isTrue();
        assertThat(server.get("secretReadable").asBoolean())
                .as("and the screen can say it is unusable").isFalse();
    }

    @Test
    @DisplayName("saving without a secret keeps the stored one")
    void blankSecretKeepsTheStoredOne() throws Exception {
        Session admin = admin();
        radiusAccepting(1, unique("someone"), "irrelevant");
        String before = jdbc.queryForObject(
                "SELECT shared_secret_enc FROM radius_server WHERE ordinal = 1", String.class);

        // Change the port and nothing else -- which is what an untouched masked
        // field submits. Retyping a credential to edit a port number is how
        // credentials end up in a text file somewhere.
        put(admin, "/api/admin/radius-settings", """
                {"enabled":true,"timeoutSeconds":2,"retries":1,"nasIdentifier":"inventory",
                 "servers":[{"host":"127.0.0.1","port":1899,"sharedSecret":null}]}
                """);

        assertThat(jdbc.queryForObject(
                "SELECT port FROM radius_server WHERE ordinal = 1", Integer.class)).isEqualTo(1899);
        assertThat(jdbc.queryForObject(
                "SELECT shared_secret_enc FROM radius_server WHERE ordinal = 1", String.class))
                .as("the stored secret is untouched").isEqualTo(before);
    }

    // -----------------------------------------------------------------
    // Just enough RFC 2865 to be a real server
    // -----------------------------------------------------------------

    /**
     * Accepts exactly one username/password pair and rejects everything else.
     * The password arrives XOR-ed against MD5(secret + request authenticator) in
     * 16-byte chunks, which this undoes -- so a wrong shared secret produces a
     * garbled password and a rejection, exactly as a real server would.
     */
    private static final class FakeRadiusServer {
        private final String username;
        private final String password;
        private DatagramSocket socket;
        private Thread thread;
        private volatile boolean running = true;
        private final java.util.concurrent.atomic.AtomicInteger requests =
                new java.util.concurrent.atomic.AtomicInteger();

        FakeRadiusServer(String username, String password) {
            this.username = username;
            this.password = password;
        }

        int port() { return socket.getLocalPort(); }

        /** How many Access-Requests reached it. Zero proves it was never asked. */
        int requestCount() { return requests.get(); }

        void start() throws Exception {
            socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
            socket.setSoTimeout(500);
            thread = new Thread(this::serve, "fake-radius");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            if (socket != null) socket.close();
        }

        private void serve() {
            byte[] buffer = new byte[4096];
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    byte[] request = Arrays.copyOf(packet.getData(), packet.getLength());
                    requests.incrementAndGet();
                    boolean ok = accepts(request);

                    // Access-Accept (2) or Access-Reject (3), 20 bytes, with the
                    // response authenticator the client verifies.
                    byte[] reply = new byte[20];
                    reply[0] = (byte) (ok ? 2 : 3);
                    reply[1] = request[1];                       // echo the identifier
                    reply[2] = 0;
                    reply[3] = 20;

                    MessageDigest md5 = MessageDigest.getInstance("MD5");
                    md5.update(reply, 0, 4);
                    md5.update(request, 4, 16);                  // the request authenticator
                    md5.update(SHARED_SECRET.getBytes("UTF-8"));
                    System.arraycopy(md5.digest(), 0, reply, 4, 16);

                    socket.send(new DatagramPacket(reply, reply.length,
                            packet.getAddress(), packet.getPort()));
                } catch (Exception e) {
                    if (!running) return;   // closed socket during teardown
                }
            }
        }

        private boolean accepts(byte[] request) throws Exception {
            byte[] authenticator = Arrays.copyOfRange(request, 4, 20);
            String user = null;
            byte[] encoded = null;

            int i = 20;
            while (i + 2 <= request.length) {
                int type = request[i] & 0xFF;
                int length = request[i + 1] & 0xFF;
                if (length < 2 || i + length > request.length) break;
                byte[] value = Arrays.copyOfRange(request, i + 2, i + length);
                if (type == 1) user = new String(value, "UTF-8");        // User-Name
                if (type == 2) encoded = value;                          // User-Password
                i += length;
            }
            if (user == null || encoded == null) return false;
            return user.equals(username) && password.equals(decode(encoded, authenticator));
        }

        private String decode(byte[] encoded, byte[] authenticator) throws Exception {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] plain = new byte[encoded.length];
            byte[] previous = authenticator;
            for (int block = 0; block < encoded.length; block += 16) {
                md5.reset();
                md5.update(SHARED_SECRET.getBytes("UTF-8"));
                md5.update(previous);
                byte[] pad = md5.digest();
                for (int j = 0; j < 16 && block + j < encoded.length; j++) {
                    plain[block + j] = (byte) (encoded[block + j] ^ pad[j]);
                }
                previous = Arrays.copyOfRange(encoded, block, Math.min(block + 16, encoded.length));
            }
            // The password is null-padded to a 16-byte boundary on the way in.
            int end = plain.length;
            while (end > 0 && plain[end - 1] == 0) end--;
            return new String(plain, 0, end, "UTF-8");
        }
    }
}
