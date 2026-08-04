package com.holidayleave.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Strongly-typed application configuration bound from application.properties.
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String dataDir = "data";
    private String reportOutputDir = "reports";
    private String excelFilePaths = "";
    private int syncIntervalSeconds = 300;
    private String loginUsername = "admin";
    private String loginPasswordHash = "";

    private Llm llm = new Llm();
    private Box box = new Box();

    public static class Llm {
        private String apiKey = "";
        private String baseUrl = "http://127.0.0.1:11434/v1";
        private String model = "llama3.2";
        private double temperature = 0.0;
        private int maxTokens = 1024;
        private String watsonxProjectId = "";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public String getWatsonxProjectId() { return watsonxProjectId; }
        public void setWatsonxProjectId(String watsonxProjectId) { this.watsonxProjectId = watsonxProjectId; }
    }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public String getReportOutputDir() { return reportOutputDir; }
    public void setReportOutputDir(String reportOutputDir) { this.reportOutputDir = reportOutputDir; }
    public String getExcelFilePaths() { return excelFilePaths; }
    public void setExcelFilePaths(String excelFilePaths) { this.excelFilePaths = excelFilePaths; }
    public int getSyncIntervalSeconds() { return syncIntervalSeconds; }
    public void setSyncIntervalSeconds(int syncIntervalSeconds) { this.syncIntervalSeconds = syncIntervalSeconds; }
    public String getLoginUsername() { return loginUsername; }
    public void setLoginUsername(String loginUsername) { this.loginUsername = loginUsername; }
    public String getLoginPasswordHash() { return loginPasswordHash; }
    public void setLoginPasswordHash(String loginPasswordHash) { this.loginPasswordHash = loginPasswordHash; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
    public Box getBox() { return box; }
    public void setBox(Box box) { this.box = box; }

    public static class Box {
        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
        private String enterpriseId = "";
        private String folderId = "";
        private String jwtPrivateKey = "";
        private String jwtPrivateKeyPassphrase = "";
        private String jwtPublicKeyId = "";
        private int retryBackoffSeconds = 60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getEnterpriseId() { return enterpriseId; }
        public void setEnterpriseId(String enterpriseId) { this.enterpriseId = enterpriseId; }
        public String getFolderId() { return folderId; }
        public void setFolderId(String folderId) { this.folderId = folderId; }
        public String getJwtPrivateKey() { return jwtPrivateKey; }
        public void setJwtPrivateKey(String jwtPrivateKey) { this.jwtPrivateKey = jwtPrivateKey; }
        public String getJwtPrivateKeyPassphrase() { return jwtPrivateKeyPassphrase; }
        public void setJwtPrivateKeyPassphrase(String v) { this.jwtPrivateKeyPassphrase = v; }
        public String getJwtPublicKeyId() { return jwtPublicKeyId; }
        public void setJwtPublicKeyId(String jwtPublicKeyId) { this.jwtPublicKeyId = jwtPublicKeyId; }
        public int getRetryBackoffSeconds() { return retryBackoffSeconds; }
        public void setRetryBackoffSeconds(int retryBackoffSeconds) { this.retryBackoffSeconds = retryBackoffSeconds; }
    }
}
