package com.midhudsonfiber.inventory.security;

import com.midhudsonfiber.inventory.domain.LdapSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.StartTlsRequest;
import javax.naming.ldap.StartTlsResponse;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * One conversation with the directory: find the person, prove the password,
 * read their groups.
 *
 * <p>Shared by {@link LdapAuthenticationProvider} and the settings screen's
 * test button, exactly as {@link RadiusClientRunner} is — so what an
 * administrator tests is what a sign-in does, and the two cannot drift.
 *
 * <p><b>Bind order matters and is the security property.</b> The user's own
 * password is verified by binding <em>as them</em>. Nothing here compares a
 * password to anything it read out of the directory, because a directory that
 * would hand back a password hash is not one you should be reading passwords
 * from. A wrong password is an LDAP bind failure and nothing else.
 *
 * <p><b>An empty password is rejected before it reaches the server.</b> LDAP
 * treats a bind with an empty credential as an <em>anonymous</em> bind and
 * returns success — so a naive implementation lets anybody in as anybody by
 * leaving the password box blank. This is the single most common way to build
 * an LDAP authentication bypass, and the check is here rather than at the
 * caller so no future caller can forget it.
 *
 * <p>Talks JNDI, which is in the JDK. No LDAP library ships with this
 * application.
 */
@Component
public class LdapClientRunner {

    private static final Logger log = LoggerFactory.getLogger(LdapClientRunner.class);

    /** What a sign-in learned, when it succeeded. */
    public record DirectoryUser(String username, String distinguishedName,
                                String email, List<String> groups) {}

    /**
     * Why an attempt did not produce a user.
     *
     * <p>{@code REJECTED} and {@code UNREACHABLE} are kept apart all the way to
     * the sign-in screen. Telling somebody their password is wrong when the
     * directory is simply down sends them to reset a password that was always
     * right.
     */
    public enum Failure { REJECTED, NOT_FOUND, UNREACHABLE, MISCONFIGURED }

    public static class LdapFailure extends RuntimeException {
        private final Failure kind;
        public LdapFailure(Failure kind, String message) { super(message); this.kind = kind; }
        public LdapFailure(Failure kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
        public Failure kind() { return kind; }
    }

    /**
     * Authenticates one person and returns what the directory knows about them.
     *
     * @param bindPassword the decrypted service-account password, or null when
     *                     {@code upnSuffix} is in use and no service account exists
     */
    public DirectoryUser authenticate(LdapSettings settings, String bindPassword,
                                      String username, String password) {
        // Before anything reaches the network. See the class note: an empty
        // credential is an anonymous bind, which succeeds.
        if (password == null || password.isEmpty()) {
            throw new LdapFailure(Failure.REJECTED, "An empty password is never a valid sign-in.");
        }
        if (username == null || username.isBlank()) {
            throw new LdapFailure(Failure.REJECTED, "No username supplied.");
        }
        if (!settings.isUsable()) {
            throw new LdapFailure(Failure.MISCONFIGURED, "LDAP is not fully configured.");
        }

        // Step 1: find the person. Either as themselves (UPN bind, no service
        // account anywhere) or through the read-only service account.
        String lookupPrincipal;
        String lookupCredential;
        if (notBlank(settings.getUpnSuffix())) {
            lookupPrincipal = username + "@" + settings.getUpnSuffix().replaceFirst("^@", "");
            lookupCredential = password;
        } else {
            lookupPrincipal = settings.getBindDn();
            lookupCredential = bindPassword;
            if (lookupCredential == null || lookupCredential.isEmpty()) {
                throw new LdapFailure(Failure.MISCONFIGURED,
                        "A service account is configured but its password could not be read.");
            }
        }

        LdapContext context = null;
        StartTlsResponse tls = null;
        try {
            context = connect(settings, lookupPrincipal, lookupCredential);
            tls = null;

            SearchResult found = findUser(context, settings, username);
            if (found == null) {
                throw new LdapFailure(Failure.NOT_FOUND,
                        "No account matching that username exists in the directory.");
            }
            String dn = found.getNameInNamespace();

            // Step 2: prove the password by binding AS the person. Skipped when
            // the lookup already was that bind -- a UPN bind with their own
            // password has proved it, and a second one only doubles the load
            // on the domain controller and the failure count on their account.
            if (!notBlank(settings.getUpnSuffix())) {
                LdapContext asUser = null;
                try {
                    asUser = connect(settings, dn, password);
                } finally {
                    close(asUser);
                }
            }

            return new DirectoryUser(username, dn, attribute(found, "mail"),
                    groupsOf(found, settings.getGroupAttribute()));

        } catch (AuthenticationException e) {
            // The directory said no. Never dressed up as anything else.
            throw new LdapFailure(Failure.REJECTED, "The directory rejected those credentials.", e);
        } catch (NamingException e) {
            log.warn("LDAP at {}:{} could not be reached or queried: {}",
                    settings.getHost(), settings.getPort(), e.getMessage());
            throw new LdapFailure(Failure.UNREACHABLE,
                    "The directory server could not be reached.", e);
        } finally {
            if (tls != null) {
                try { tls.close(); } catch (Exception ignored) { /* closing anyway */ }
            }
            close(context);
        }
    }

    private LdapContext connect(LdapSettings settings, String principal, String credential)
            throws NamingException {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url(settings));
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, credential);

        // Without these a dead host hangs the sign-in request until the OS
        // gives up, which on Linux is minutes.
        long millis = settings.getConnectTimeoutSeconds() * 1000L;
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(millis));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(millis));

        if (settings.getTransport() == LdapSettings.Transport.STARTTLS) {
            // StartTLS has to be negotiated before the credentials are sent, so
            // the context is built unauthenticated and upgraded first.
            Hashtable<String, Object> plain = new Hashtable<>(env);
            plain.remove(Context.SECURITY_PRINCIPAL);
            plain.remove(Context.SECURITY_CREDENTIALS);
            plain.put(Context.SECURITY_AUTHENTICATION, "none");

            LdapContext ctx = new InitialLdapContext(plain, null);
            try {
                StartTlsResponse response = (StartTlsResponse) ctx.extendedOperation(new StartTlsRequest());
                response.negotiate();
            } catch (java.io.IOException e) {
                close(ctx);
                throw new LdapFailure(Failure.UNREACHABLE, "StartTLS negotiation failed.", e);
            }
            ctx.addToEnvironment(Context.SECURITY_AUTHENTICATION, "simple");
            ctx.addToEnvironment(Context.SECURITY_PRINCIPAL, principal);
            ctx.addToEnvironment(Context.SECURITY_CREDENTIALS, credential);
            ctx.reconnect(null);   // the bind actually happens here
            return ctx;
        }

        return new InitialLdapContext(env, null);
    }

    private static String url(LdapSettings settings) {
        String scheme = settings.getTransport() == LdapSettings.Transport.LDAPS ? "ldaps" : "ldap";
        return scheme + "://" + settings.getHost() + ":" + settings.getPort();
    }

    private SearchResult findUser(LdapContext context, LdapSettings settings, String username)
            throws NamingException {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setCountLimit(2);
        controls.setTimeLimit((int) (settings.getConnectTimeoutSeconds() * 1000L));
        // Ask for the group attribute by name: Active Directory does not return
        // memberOf among the operational attributes unless it is requested.
        controls.setReturningAttributes(new String[]{settings.getGroupAttribute(), "mail", "cn"});

        NamingEnumeration<SearchResult> results = context.search(
                settings.getUserSearchBase(),
                settings.getUserSearchFilter(),
                new Object[]{username},
                controls);
        try {
            if (!results.hasMore()) return null;
            SearchResult first = results.next();

            // Two matches means the filter is not identifying one person, and
            // guessing which is the sort of thing that signs somebody in as
            // their namesake.
            if (results.hasMore()) {
                throw new LdapFailure(Failure.MISCONFIGURED,
                        "The user search filter matched more than one directory entry.");
            }
            return first;
        } finally {
            try { results.close(); } catch (NamingException ignored) { /* done with it */ }
        }
    }

    private static List<String> groupsOf(SearchResult entry, String attributeName) throws NamingException {
        List<String> groups = new ArrayList<>();
        Attribute attribute = entry.getAttributes().get(attributeName);
        if (attribute == null) return groups;
        NamingEnumeration<?> values = attribute.getAll();
        while (values.hasMore()) {
            Object value = values.next();
            if (value != null) groups.add(value.toString());
        }
        return groups;
    }

    private static String attribute(SearchResult entry, String name) throws NamingException {
        Attribute attribute = entry.getAttributes().get(name);
        return attribute == null ? null : String.valueOf(attribute.get());
    }

    private static void close(Context context) {
        if (context == null) return;
        try { context.close(); } catch (NamingException ignored) { /* closing anyway */ }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
