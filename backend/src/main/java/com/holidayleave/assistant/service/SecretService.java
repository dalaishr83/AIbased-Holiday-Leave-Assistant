package com.holidayleave.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holidayleave.assistant.config.AppProperties;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * File-backed credential store for admin and employee roles.
 * Credentials are persisted in /data/secret/secret.json.
 * Uses BCrypt for all password hashing — same algorithm as the existing auth flow.
 */
@Service
public class SecretService {

    private static final Logger log = LoggerFactory.getLogger(SecretService.class);

    @Autowired
    private AppProperties props;

    private final ObjectMapper mapper = new ObjectMapper();
    private Path secretFilePath;

    @PostConstruct
    public void init() throws IOException {
        Path dataDir = Paths.get(props.getDataDir()).toAbsolutePath();
        secretFilePath = dataDir.resolve("secret").resolve("secret.json");
        Files.createDirectories(secretFilePath.getParent());

        if (!Files.exists(secretFilePath)) {
            // Bootstrap from application.properties / env credentials for the admin role;
            // generate a placeholder employee credential so the file exists on first boot.
            Map<String, Map<String, String>> credentials = new LinkedHashMap<>();

            Map<String, String> adminCred = new LinkedHashMap<>();
            adminCred.put("username", props.getLoginUsername().isEmpty() ? "admin" : props.getLoginUsername());
            // Reuse the existing hash if already configured, otherwise hash a default.
            String existingHash = props.getLoginPasswordHash();
            adminCred.put("hash", existingHash != null && !existingHash.isEmpty()
                    ? existingHash
                    : BCrypt.hashpw("admin", BCrypt.gensalt()));
            credentials.put("admin", adminCred);

            Map<String, String> empCred = new LinkedHashMap<>();
            empCred.put("username", "employee");
            empCred.put("hash", BCrypt.hashpw("employee", BCrypt.gensalt()));
            credentials.put("employee", empCred);

            save(credentials);
            log.info("SecretService: bootstrapped secret.json");
        }
    }

    /** Returns the credentials map keyed by role ("admin", "employee"). */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, String>> readCredentials() {
        try {
            return mapper.readValue(secretFilePath.toFile(),
                    mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (IOException e) {
            log.error("Failed to read secret.json", e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * Returns the stored BCrypt hash for the given role, or null if not found.
     */
    public String getHash(String role) {
        Map<String, Map<String, String>> creds = readCredentials();
        Map<String, String> entry = creds.get(role);
        if (entry == null) return null;
        return entry.get("hash");
    }

    /**
     * Returns the username for the given role, or null if not found.
     */
    public String getUsername(String role) {
        Map<String, Map<String, String>> creds = readCredentials();
        Map<String, String> entry = creds.get(role);
        if (entry == null) return null;
        return entry.get("username");
    }

    /**
     * Updates the password hash for the given role using BCrypt.
     * @param role       "admin" or "employee"
     * @param newPassword plain-text new password
     */
    public void updatePassword(String role, String newPassword) throws IOException {
        Map<String, Map<String, String>> creds = readCredentials();
        if (!creds.containsKey(role)) {
            throw new IllegalArgumentException("Unknown role: " + role);
        }
        creds.get(role).put("hash", BCrypt.hashpw(newPassword, BCrypt.gensalt()));
        save(creds);
        log.info("SecretService: password updated for role '{}'", role);
    }

    @SuppressWarnings("unchecked")
    private void save(Map<String, Map<String, String>> credentials) throws IOException {
        Path tmp = secretFilePath.getParent().resolve("." + UUID.randomUUID() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), credentials);
            Files.move(tmp, secretFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
