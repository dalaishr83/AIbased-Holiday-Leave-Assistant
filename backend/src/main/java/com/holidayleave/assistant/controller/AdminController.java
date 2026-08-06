package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.excel.WorkingExcelWriter;
import com.holidayleave.assistant.model.FileInfo;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.VacationType;
import com.holidayleave.assistant.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Admin-only controller.
 * All routes under /admin/** (pages) and /api/admin/** (REST) require role="admin".
 * The AuthInterceptor enforces this — no additional annotation needed here.
 */
@Controller
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Autowired private AppState appState;
    @Autowired private PlannerExcelReader reader;
    @Autowired private WorkingExcelWriter writer;
    @Autowired private VacationTypeService typeService;
    @Autowired private RestrictedVacationTypeService restrictedTypeService;
    @Autowired private SecretService secretService;
    @Autowired private AuditService auditService;
    @Autowired private SyncService syncService;

    // ── Page routes ───────────────────────────────────────────────────────────

    @GetMapping("/admin/settings")
    public String settingsPage(Model model) {
        model.addAttribute("vacationTypes", typeService.findAll());
        model.addAttribute("restrictedTypes", restrictedTypeService.getRestrictedTypes());
        return "admin-settings";
    }

    @GetMapping("/admin/approvals")
    public String approvalsPage(Model model) {
        List<Map<String, Object>> pcRecords = loadPcRecords();
        model.addAttribute("pcRecords", pcRecords);
        return "admin-approvals";
    }

    @GetMapping("/admin/audit-log")
    public String auditLogPage() {
        return "admin-audit-log";
    }

    // ── Audit log API ─────────────────────────────────────────────────────────

    /** GET /api/admin/audit-log — returns all audit entries, most recent first */
    @GetMapping("/api/admin/audit-log")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getAuditLog() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("entries", auditService.readAll());
        return ResponseEntity.ok(r);
    }

    // ── Settings API ──────────────────────────────────────────────────────────

    /** GET /api/admin/settings/restricted-types — returns current restricted codes */
    @GetMapping("/api/admin/settings/restricted-types")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getRestrictedTypes() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("restricted_types", restrictedTypeService.getRestrictedTypes());
        r.put("vacation_types", typeService.findAll());
        return ResponseEntity.ok(r);
    }

    /** POST /api/admin/settings/restricted-types — replaces restricted codes list */
    @PostMapping("/api/admin/settings/restricted-types")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setRestrictedTypes(@RequestBody Map<String, Object> body) {
        Object raw = body.get("restricted_types");
        if (!(raw instanceof List)) {
            return ResponseEntity.badRequest().body(err("restricted_types must be an array of code strings."));
        }
        List<String> codes = new ArrayList<>();
        for (Object o : (List<?>) raw) codes.add(String.valueOf(o));
        try {
            restrictedTypeService.setRestrictedTypes(codes);
            auditService.log("restricted_types_updated", "admin", null,
                    "Updated restricted types: " + codes, "success", "api");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", "Restricted vacation types updated.");
            r.put("restricted_types", restrictedTypeService.getRestrictedTypes());
            return ResponseEntity.ok(r);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("Failed to save restricted types: " + e.getMessage()));
        }
    }

    /**
     * GET /api/admin/settings/employee-credentials
     * Returns [{username, employee_name}] for every employee-role credential entry.
     * Used by the Settings page to populate the employee password-reset dropdown.
     */
    @GetMapping("/api/admin/settings/employee-credentials")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getEmployeeCredentials() {
        Map<String, Map<String, String>> all = secretService.readCredentials();
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> entry : all.values()) {
            if ("employee".equals(entry.get("role"))) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("username",      entry.get("username"));
                item.put("employee_name", entry.get("employee_name"));
                result.add(item);
            }
        }
        // Sort by employee_name for a predictable dropdown order.
        result.sort((a, b) -> {
            String na = a.get("employee_name"); String nb = b.get("employee_name");
            if (na == null) na = ""; if (nb == null) nb = "";
            return na.compareToIgnoreCase(nb);
        });
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("employees", result);
        return ResponseEntity.ok(r);
    }

    /** POST /api/admin/settings/password-reset — update password for a credential key */
    @PostMapping("/api/admin/settings/password-reset")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody Map<String, String> body) {
        String credKey  = body.get("role");          // field name kept as "role" for backward compat
        String password = body.get("new_password");
        if (credKey == null || credKey.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("role is required."));
        if (password == null || password.length() < 6)
            return ResponseEntity.badRequest().body(err("new_password must be at least 6 characters."));
        // Accept any key that actually exists in the credential store.
        if (secretService.findByUsername(credKey) == null)
            return ResponseEntity.badRequest().body(err("Unknown credential key: '" + credKey + "'."));
        try {
            secretService.updatePassword(credKey, password);
            auditService.log("password_reset", "admin", null,
                    "Password reset for credential: " + credKey, "success", "api");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", "Password for '" + credKey + "' updated successfully.");
            return ResponseEntity.ok(r);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("Failed to update password: " + e.getMessage()));
        }
    }

    // ── File management API ───────────────────────────────────────────────────

    /** DELETE /api/admin/files — delete a master file by name */
    @DeleteMapping("/api/admin/files")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteFile(@RequestBody Map<String, String> body) {
        String filename = body.get("filename");
        if (filename == null || filename.trim().isEmpty())
            return ResponseEntity.badRequest().body(err("filename is required."));

        // Security: only allow simple filenames (no path traversal)
        if (filename.contains("/") || filename.contains("\\") || filename.contains(".."))
            return ResponseEntity.badRequest().body(err("Invalid filename."));

        Path masterPath  = Paths.get(appState.getDataDir(), filename);
        Path workingPath = Paths.get(appState.getWorkingDir(), filename);
        Path uploadPath  = Paths.get(appState.getUploadsDir(), filename);

        if (!masterPath.toFile().exists())
            return ResponseEntity.status(404).body(err("File not found: " + filename));

        try {
            Files.deleteIfExists(masterPath);
            Files.deleteIfExists(workingPath);
            Files.deleteIfExists(uploadPath);

            // Evict from cache and reset state
            reader.evict(masterPath.toAbsolutePath().toString());
            appState.setLoadedFiles(Collections.<String>emptyList());
            appState.setActiveFiles(Collections.<String>emptyList());
            appState.refreshKnownFiles();

            auditService.log("file_deleted", "admin", null, "Deleted file: " + filename, "success", "api");

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", "File '" + filename + "' deleted.");
            r.put("files",   appState.getKnownFiles());
            return ResponseEntity.ok(r);
        } catch (IOException e) {
            log.error("File delete failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(err("Failed to delete file: " + e.getMessage()));
        }
    }

    // ── PC Approval API ───────────────────────────────────────────────────────

    /** GET /api/admin/approvals/pc-records — list all PC vacation entries */
    @GetMapping("/api/admin/approvals/pc-records")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getPcRecords() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pc_records", loadPcRecords());
        return ResponseEntity.ok(r);
    }

    /**
     * POST /api/admin/approvals/approve-pc — convert selected PC entries to V in working file.
     * Body: { "approvals": [ { "employee_name":"...", "start_date":"YYYY-MM-DD", "end_date":"YYYY-MM-DD" }, ... ] }
     */
    @PostMapping("/api/admin/approvals/approve-pc")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> approvePc(@RequestBody Map<String, Object> body) {
        Object raw = body.get("approvals");
        if (!(raw instanceof List))
            return ResponseEntity.badRequest().body(err("approvals must be an array."));

        VacationType vType = typeService.findByCode("V").orElse(null);
        if (vType == null)
            return ResponseEntity.status(500).body(err("Vacation type 'V' not found in configuration."));

        List<?> approvalList = (List<?>) raw;
        int approved = 0;
        List<String> errors = new ArrayList<>();

        for (Object item : approvalList) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> entry = (Map<?, ?>) item;
            String empName  = (String) entry.get("employee_name");
            String startStr = (String) entry.get("start_date");
            String endStr   = (String) entry.get("end_date");

            if (empName == null || startStr == null || endStr == null) {
                errors.add("Skipped entry with missing fields.");
                continue;
            }
            try {
                LocalDate start = LocalDate.parse(startStr);
                LocalDate end   = LocalDate.parse(endStr);
                int year = start.getYear();

                String workingPath = appState.getWorkingDir() + "/eIndkomst vacation " + year + ".xlsx";
                ensureWorkingCopy(workingPath, year);

                // Delete existing PC cells then write V cells
                writer.deleteVacation(workingPath, empName, start, end);
                LeaveRecord newRecord = new LeaveRecord(empName, start, end,
                        VacationCreationService.countWeekdays(start, end), vType.label(), "Approved PC");
                writer.addVacation(workingPath, newRecord, vType);
                reader.evict(Paths.get(appState.getDataDir(), "eIndkomst vacation " + year + ".xlsx")
                        .toAbsolutePath().toString());
                approved++;
                auditService.log("pc_approved", "admin", empName,
                        "PC→V approved " + startStr + " to " + endStr, "success", "api");
            } catch (Exception e) {
                errors.add("Failed for " + empName + " (" + startStr + "): " + e.getMessage());
                log.warn("PC approval error: {}", e.getMessage());
            }
        }

        if (approved > 0) syncService.triggerSync();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("approved", approved);
        r.put("errors",   errors);
        r.put("message",  approved + " PC vacation(s) approved as Vacation (V).");
        return ResponseEntity.ok(r);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Map<String, Object>> loadPcRecords() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<LeaveRecord> all = new ArrayList<>();
            for (String p : appState.getLoadedFiles()) {
                if (new File(p).exists()) all.addAll(reader.load(p));
            }
            for (LeaveRecord r : all) {
                if ("PC".equalsIgnoreCase(r.leaveType()) ||
                    "Personal Choice Holiday".equalsIgnoreCase(r.leaveType())) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("employee_name", r.employeeName());
                    m.put("start_date",    r.startDate().toString());
                    m.put("end_date",      r.endDate().toString());
                    m.put("days",          r.days());
                    m.put("year",          r.year());
                    result.add(m);
                }
            }
        } catch (IOException e) {
            log.error("Failed to load PC records: {}", e.getMessage());
        }
        return result;
    }

    private void ensureWorkingCopy(String workingPath, int year) throws IOException {
        if (!new File(workingPath).exists()) {
            String masterPath = appState.getDataDir() + "/eIndkomst vacation " + year + ".xlsx";
            if (new File(masterPath).exists()) {
                Files.createDirectories(Paths.get(workingPath).getParent());
                Files.copy(Paths.get(masterPath), Paths.get(workingPath), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}
