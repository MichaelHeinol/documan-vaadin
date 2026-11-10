package de.documan.config;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Einstellungen aus der Umgebung. Die Tests setzen einzelne Werte je Testfall um. */
@ConfigurationProperties("documan")
public class DocuManProperties {

    private String secretKey;
    private String adminUser = "admin";
    private String adminPassword = "";
    private String adminEmail = "";
    private String siteUrl = "";
    private String emailHost = "";
    private int emailPort = 587;
    private String emailSecurity = "starttls";
    private String emailHostUser = "";
    private String emailHostPassword = "";
    private String emailFrom = "";
    private boolean registrationEnabled;
    private String dailyReportTime = "07:00";
    private String version = "dev";
    private String microsoftClientId = "";
    private String microsoftTenant = "common";
    private String microsoftLoginUrl = "https://login.microsoftonline.com";
    private boolean https;
    private List<String> allowedHosts = List.of("*");
    private ZoneId timeZone = ZoneId.of("Europe/Berlin");

    private Path mediaRoot;
    private Path consumeDir;
    private Path backupDir;
    // Nur zur Anzeige: Wo die Ordner auf dem NAS liegen, weiß das Programm im Container sonst nicht.
    private String consumeHostDir = "";
    private String browseHostDir = "";

    private int consumePollSeconds = 10;
    private int consumeStableSeconds = 10;
    private int maxFileMb = 500;

    private String ocrLanguages = "deu+eng";
    private int ocrJobs = 1;
    private String ocrOutputType = "pdfa";
    private int ocrTimeoutSeconds = 3600;

    private String dbHost;
    private int dbPort = 5432;
    private String dbName;
    private String dbUser;
    private String dbPassword;

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getAdminUser() {
        return adminUser;
    }

    public void setAdminUser(String adminUser) {
        this.adminUser = adminUser;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public void setAdminEmail(String adminEmail) {
        this.adminEmail = adminEmail;
    }

    public String getSiteUrl() {
        return siteUrl;
    }

    public void setSiteUrl(String siteUrl) {
        this.siteUrl = siteUrl;
    }

    public String getEmailHost() {
        return emailHost;
    }

    public void setEmailHost(String emailHost) {
        this.emailHost = emailHost;
    }

    public int getEmailPort() {
        return emailPort;
    }

    public void setEmailPort(int emailPort) {
        this.emailPort = emailPort;
    }

    public String getEmailSecurity() {
        return emailSecurity;
    }

    public void setEmailSecurity(String emailSecurity) {
        this.emailSecurity = emailSecurity;
    }

    public String getEmailHostUser() {
        return emailHostUser;
    }

    public void setEmailHostUser(String emailHostUser) {
        this.emailHostUser = emailHostUser;
    }

    public String getEmailHostPassword() {
        return emailHostPassword;
    }

    public void setEmailHostPassword(String emailHostPassword) {
        this.emailHostPassword = emailHostPassword;
    }

    public String getMicrosoftClientId() {
        return microsoftClientId;
    }

    public void setMicrosoftClientId(String microsoftClientId) {
        this.microsoftClientId = microsoftClientId;
    }

    public String getMicrosoftTenant() {
        return microsoftTenant;
    }

    public void setMicrosoftTenant(String microsoftTenant) {
        this.microsoftTenant = microsoftTenant;
    }

    public String getMicrosoftLoginUrl() {
        return microsoftLoginUrl;
    }

    public void setMicrosoftLoginUrl(String microsoftLoginUrl) {
        this.microsoftLoginUrl = microsoftLoginUrl;
    }

    public String getEmailFrom() {
        return emailFrom;
    }

    public void setEmailFrom(String emailFrom) {
        this.emailFrom = emailFrom;
    }

    public boolean isRegistrationEnabled() {
        return registrationEnabled;
    }

    public String getDailyReportTime() {
        return dailyReportTime;
    }

    public void setDailyReportTime(String dailyReportTime) {
        this.dailyReportTime = dailyReportTime;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public void setRegistrationEnabled(boolean registrationEnabled) {
        this.registrationEnabled = registrationEnabled;
    }

    /** Ohne Mailversand käme niemand an den Bestätigungslink. */
    public boolean registrationOpen() {
        return registrationEnabled && !emailHost.isEmpty();
    }

    public boolean isHttps() {
        return https;
    }

    public void setHttps(boolean https) {
        this.https = https;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    public ZoneId getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(ZoneId timeZone) {
        this.timeZone = timeZone;
    }

    public Path getMediaRoot() {
        return mediaRoot;
    }

    public void setMediaRoot(Path mediaRoot) {
        this.mediaRoot = mediaRoot;
    }

    public Path getConsumeDir() {
        return consumeDir;
    }

    public void setConsumeDir(Path consumeDir) {
        this.consumeDir = consumeDir;
    }

    public String getConsumeHostDir() {
        return consumeHostDir;
    }

    public void setConsumeHostDir(String consumeHostDir) {
        this.consumeHostDir = consumeHostDir;
    }

    public String getBrowseHostDir() {
        return browseHostDir;
    }

    public void setBrowseHostDir(String browseHostDir) {
        this.browseHostDir = browseHostDir;
    }

    public Path getBackupDir() {
        return backupDir;
    }

    public void setBackupDir(Path backupDir) {
        this.backupDir = backupDir;
    }

    public int getConsumePollSeconds() {
        return consumePollSeconds;
    }

    public void setConsumePollSeconds(int consumePollSeconds) {
        this.consumePollSeconds = consumePollSeconds;
    }

    public int getConsumeStableSeconds() {
        return consumeStableSeconds;
    }

    public void setConsumeStableSeconds(int consumeStableSeconds) {
        this.consumeStableSeconds = consumeStableSeconds;
    }

    public int getMaxFileMb() {
        return maxFileMb;
    }

    public void setMaxFileMb(int maxFileMb) {
        this.maxFileMb = maxFileMb;
    }

    public String getOcrLanguages() {
        return ocrLanguages;
    }

    public void setOcrLanguages(String ocrLanguages) {
        this.ocrLanguages = ocrLanguages;
    }

    public int getOcrJobs() {
        return ocrJobs;
    }

    public void setOcrJobs(int ocrJobs) {
        this.ocrJobs = ocrJobs;
    }

    public String getOcrOutputType() {
        return ocrOutputType;
    }

    public void setOcrOutputType(String ocrOutputType) {
        this.ocrOutputType = ocrOutputType;
    }

    public int getOcrTimeoutSeconds() {
        return ocrTimeoutSeconds;
    }

    public void setOcrTimeoutSeconds(int ocrTimeoutSeconds) {
        this.ocrTimeoutSeconds = ocrTimeoutSeconds;
    }

    public String getDbHost() {
        return dbHost;
    }

    public void setDbHost(String dbHost) {
        this.dbHost = dbHost;
    }

    public int getDbPort() {
        return dbPort;
    }

    public void setDbPort(int dbPort) {
        this.dbPort = dbPort;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getDbUser() {
        return dbUser;
    }

    public void setDbUser(String dbUser) {
        this.dbUser = dbUser;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public void setDbPassword(String dbPassword) {
        this.dbPassword = dbPassword;
    }
}
