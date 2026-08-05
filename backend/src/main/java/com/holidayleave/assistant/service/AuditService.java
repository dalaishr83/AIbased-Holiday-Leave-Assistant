package com.holidayleave.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holidayleave.assistant.config.AppProperties;
import com.holidayleave.assistant.model.AuditLogEntry;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only JSONL audit log writer at data/audit.log.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    @Autowired
    private AppProperties props;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Lock lock = new ReentrantLock();
    private Path auditFilePath;

    @PostConstruct
    public void init() throws IOException {
        auditFilePath = Paths.get(props.getDataDir(), "audit.log");
        Files.createDirectories(auditFilePath.getParent());
    }

    public void log(String eventType, String user, String employee, String details, String status, String source) {
        AuditLogEntry entry = new AuditLogEntry(
                ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                eventType, user, employee, details, status, source
        );
        lock.lock();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(auditFilePath.toFile(), true))) {
            writer.write(mapper.writeValueAsString(entry));
            writer.newLine();
        } catch (IOException e) {
            log.error("Audit log write failed: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Read all audit log entries in reverse-chronological order (most recent first).
     * Returns an empty list if the log file does not yet exist.
     */
    public List<AuditLogEntry> readAll() {
        if (!Files.exists(auditFilePath)) {
            return Collections.emptyList();
        }
        List<AuditLogEntry> entries = new ArrayList<>();
        lock.lock();
        try (BufferedReader reader = Files.newBufferedReader(auditFilePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    try {
                        entries.add(mapper.readValue(line, AuditLogEntry.class));
                    } catch (Exception e) {
                        log.warn("Skipping malformed audit log line: {}", e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.error("Audit log read failed: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
        Collections.reverse(entries);
        return entries;
    }
}
