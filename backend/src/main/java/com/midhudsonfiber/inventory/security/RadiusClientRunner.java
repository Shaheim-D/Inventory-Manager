package com.midhudsonfiber.inventory.security;

import com.midhudsonfiber.inventory.domain.RadiusServer;
import com.midhudsonfiber.inventory.domain.RadiusSettings;
import com.midhudsonfiber.inventory.repo.RadiusServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.tinyradius.attribute.RadiusAttribute;
import org.tinyradius.packet.AccessRequest;
import org.tinyradius.packet.RadiusPacket;
import org.tinyradius.util.RadiusClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends one Access-Request to the configured servers, in order, and reports
 * what came back. Shared by sign-in and by the settings screen's test button so
 * the two cannot drift -- a test that exercised a different code path from the
 * real thing would be worse than no test button.
 *
 * <p><b>Failover is for servers that do not answer, not for servers that say
 * no.</b> A reject from a server that replied is authoritative and stops there;
 * only a timeout, an unreachable host, or a reply that cannot be verified moves
 * on to the next one. That is how a NAS behaves, and the alternative is worse
 * than it looks: retrying a rejected password against every server turns one
 * bad sign-in into a failed attempt on each of them, which is how an account
 * gets locked out in Active Directory by an application nobody suspects.
 */
@Component
public class RadiusClientRunner {

    private static final Logger log = LoggerFactory.getLogger(RadiusClientRunner.class);

    private final RadiusServerRepository servers;
    private final SecretCipher cipher;

    public RadiusClientRunner(RadiusServerRepository servers, SecretCipher cipher) {
        this.servers = servers;
        this.cipher = cipher;
    }

    /**
     * @param outcome        what happened, once the walk stopped
     * @param serverLabel    which server produced it, or null when none answered
     * @param detail         something to show a person; never contains a credential
     * @param roleAttributes every value of the configured role attribute the
     *                       Access-Accept carried, in the order it carried them.
     *                       Empty for anything that is not an accept.
     */
    public record Attempt(Outcome outcome, String serverLabel, String detail,
                          List<String> roleAttributes) {

        Attempt(Outcome outcome, String serverLabel, String detail) {
            this(outcome, serverLabel, detail, List.of());
        }
    }

    public enum Outcome {
        /** A server replied Access-Accept. */
        ACCEPTED,
        /** A server replied Access-Reject. Authoritative: the walk stops. */
        REJECTED,
        /** No server answered. Not a wrong password. */
        UNREACHABLE,
        /** Nothing to send to, or a secret this instance cannot read. */
        NOT_CONFIGURED
    }

    public Attempt authenticate(RadiusSettings settings, String username, String password) {
        List<RadiusServer> configured = servers.findAllByOrderByOrdinalAsc();
        if (configured.isEmpty()) {
            return new Attempt(Outcome.NOT_CONFIGURED, null, "No RADIUS server is configured.");
        }

        String lastFailure = null;
        int tried = 0;

        for (RadiusServer server : configured) {
            String secret;
            try {
                secret = cipher.decrypt(server.getSharedSecretEnc());
            } catch (IllegalStateException e) {
                // The row is there but this instance cannot read it -- normally a
                // restore onto a host without the original encryption key. Skip
                // rather than abort: the other server may still be usable, and a
                // half-working sign-in beats none.
                log.error("The shared secret for {} could not be decrypted; skipping it.", server.label());
                lastFailure = server.label() + ": its stored secret could not be decrypted";
                continue;
            }
            if (secret == null || secret.isBlank()) {
                lastFailure = server.label() + ": no shared secret has been set";
                continue;
            }

            tried++;
            RadiusClient client = new RadiusClient(server.getHost(), secret);
            try {
                client.setAuthPort(server.getPort());
                client.setRetryCount(Math.max(1, settings.getRetries()));
                client.setSocketTimeout(settings.getTimeoutSeconds() * 1000);

                AccessRequest request = new AccessRequest(username, password);
                request.setAuthProtocol(AccessRequest.AUTH_PAP);
                String nas = settings.getNasIdentifier();
                if (nas != null && !nas.isBlank()) {
                    request.addAttribute("NAS-Identifier", nas);
                }

                RadiusPacket reply = client.authenticate(request);
                if (reply != null && reply.getPacketType() == RadiusPacket.ACCESS_ACCEPT) {
                    return new Attempt(Outcome.ACCEPTED, server.label(), "Accepted.",
                            roleAttributes(reply, settings.roleAttributeNumber()));
                }
                // It answered, and said no. Authoritative -- see the class note.
                return new Attempt(Outcome.REJECTED, server.label(),
                        "The server replied and rejected those credentials.");
            } catch (Exception e) {
                // No route, a timeout, or a reply whose authenticator did not
                // verify (which is what a mismatched shared secret looks like).
                // None of them mean the password is wrong, so try the next one.
                log.warn("RADIUS server {} did not answer for '{}': {}",
                        server.label(), username, rootMessage(e));
                lastFailure = server.label() + ": " + rootMessage(e);
            } finally {
                client.close();
            }
        }

        if (tried == 0) {
            return new Attempt(Outcome.NOT_CONFIGURED, null,
                    lastFailure == null ? "No RADIUS server is usable." : lastFailure);
        }
        return new Attempt(Outcome.UNREACHABLE, null,
                "No configured RADIUS server answered. Last was " + lastFailure + ".");
    }

    /**
     * Every value of the configured attribute on the reply.
     *
     * <p>Read as raw bytes and decoded as UTF-8 rather than through the
     * dictionary, because Filter-Id is defined as text and Class as opaque
     * octets -- and NPS is routinely configured to put a readable group name in
     * either. Decoding both the same way means the mapping table does not have
     * to care which one an installation picked.
     *
     * <p>A list, not a single value, because a person in two groups gets two
     * attributes and should end up with both roles.
     */
    private static List<String> roleAttributes(RadiusPacket reply, int attributeNumber) {
        List<String> values = new ArrayList<>();
        for (Object attribute : reply.getAttributes(attributeNumber)) {
            byte[] data = ((RadiusAttribute) attribute).getAttributeData();
            if (data == null || data.length == 0) continue;
            String value = new String(data, StandardCharsets.UTF_8).trim();
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }

    static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
