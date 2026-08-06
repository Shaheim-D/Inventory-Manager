package com.midhudsonfiber.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();
    private Auth auth = new Auth();
    private BrandingLimits branding = new BrandingLimits();
    private Attachments attachments = new Attachments();

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

    public static class Attachments {
        /**
         * Where uploaded files live. The schema stores a path rather than the
         * bytes, so this directory is real data -- it must be on the same
         * backup schedule as the database, and a restore that brings back only
         * the database leaves every attachment row pointing at nothing.
         */
        private String directory = "data/attachments";
        private long maxBytes = 25L * 1024 * 1024;

        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
        public long getMaxBytes() { return maxBytes; }
        public void setMaxBytes(long v) { this.maxBytes = v; }
    }

    public BootstrapAdmin getBootstrapAdmin() { return bootstrapAdmin; }
    public void setBootstrapAdmin(BootstrapAdmin v) { this.bootstrapAdmin = v; }
    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }
    public BrandingLimits getBranding() { return branding; }
    public void setBranding(BrandingLimits branding) { this.branding = branding; }
    public Attachments getAttachments() { return attachments; }
    public void setAttachments(Attachments v) { this.attachments = v; }
}
