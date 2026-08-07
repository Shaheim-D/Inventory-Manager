package com.midhudsonfiber.inventory;

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
import java.util.Arrays;

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

    private FakeRadiusServer server;

    private Session admin() {
        return signIn("admin", "BootstrapAdmin123");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        // Leave RADIUS off: these tests share a database with every other test,
        // and a stray enabled row would send their sign-ins at a dead port.
        jdbc.update("UPDATE radius_settings SET is_enabled = false, host = NULL, shared_secret_ref = NULL WHERE id = 1");
    }

    private void radiusAccepts(String username, String password) throws Exception {
        server = new FakeRadiusServer(username, password);
        server.start();
        jdbc.update("""
                UPDATE radius_settings
                   SET is_enabled = true, host = '127.0.0.1', port = ?,
                       shared_secret_ref = 'TEST_RADIUS_SECRET', timeout_seconds = 2, retries = 1
                 WHERE id = 1
                """, server.port());
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
        jdbc.update("""
                UPDATE radius_settings
                   SET is_enabled = true, host = '127.0.0.1', port = 1, timeout_seconds = 1,
                       retries = 0, shared_secret_ref = 'TEST_RADIUS_SECRET'
                 WHERE id = 1
                """);

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

    @Test
    @DisplayName("the settings screen never returns the shared secret")
    void secretIsNeverReturned() {
        Session admin = admin();
        var body = get(admin, "/api/admin/radius-settings").getBody();
        assertThat(body).isNotNull();
        assertThat(body.has("sharedSecretRef")).as("the variable's name is shown").isTrue();
        assertThat(body.has("sharedSecretResolves")).as("and whether it resolves").isTrue();
        assertThat(body.toString()).doesNotContain(SHARED_SECRET);
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

        FakeRadiusServer(String username, String password) {
            this.username = username;
            this.password = password;
        }

        int port() { return socket.getLocalPort(); }

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
