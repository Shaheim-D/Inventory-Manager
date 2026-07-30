package com.midhudsonfiber.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();
    private Auth auth = new Auth();
    private BrandingLimits branding = new BrandingLimits();
    private Ldap ldap = new Ldap();
    private ActiveDirectory activeDirectory = new ActiveDirectory();

    public static class BootstrapAdmin {
        /** Created only when app_user is empty -- never touched on later startups. */
        private String username = "admin";
        private String password = "";

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class Auth {
        private int maxFailedAttempts = 5;
        private int lockoutMinutes = 15;

        public int getMaxFailedAttempts() { return maxFailedAttempts; }
        public void setMaxFailedAttempts(int v) { this.maxFailedAttempts = v; }
        public int getLockoutMinutes() { return lockoutMinutes; }
        public void setLockoutMinutes(int v) { this.lockoutMinutes = v; }
    }

    public static class BrandingLimits {
        private long maxLogoBytes = 2 * 1024 * 1024;

        public long getMaxLogoBytes() { return maxLogoBytes; }
        public void setMaxLogoBytes(long v) { this.maxLogoBytes = v; }
    }

    public static class Ldap {
        private boolean enabled;
        private String url = "";
        private String userSearchBase = "";
        private String userSearchFilter = "(uid={0})";
        private String bindDn = "";
        private String bindPassword = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUserSearchBase() { return userSearchBase; }
        public void setUserSearchBase(String v) { this.userSearchBase = v; }
        public String getUserSearchFilter() { return userSearchFilter; }
        public void setUserSearchFilter(String v) { this.userSearchFilter = v; }
        public String getBindDn() { return bindDn; }
        public void setBindDn(String bindDn) { this.bindDn = bindDn; }
        public String getBindPassword() { return bindPassword; }
        public void setBindPassword(String v) { this.bindPassword = v; }
    }

    public static class ActiveDirectory {
        private boolean enabled;
        private String domain = "";
        private String url = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    public BootstrapAdmin getBootstrapAdmin() { return bootstrapAdmin; }
    public void setBootstrapAdmin(BootstrapAdmin v) { this.bootstrapAdmin = v; }
    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }
    public BrandingLimits getBranding() { return branding; }
    public void setBranding(BrandingLimits branding) { this.branding = branding; }
    public Ldap getLdap() { return ldap; }
    public void setLdap(Ldap ldap) { this.ldap = ldap; }
    public ActiveDirectory getActiveDirectory() { return activeDirectory; }
    public void setActiveDirectory(ActiveDirectory v) { this.activeDirectory = v; }
}
