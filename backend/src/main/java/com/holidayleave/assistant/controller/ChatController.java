package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.PendingVacation;
import com.holidayleave.assistant.model.PendingVacation.WizardState;
import com.holidayleave.assistant.model.VacationType;
import com.holidayleave.assistant.service.*;
import com.holidayleave.assistant.excel.WorkingExcelWriter;
import javax.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    @Autowired private HolidayAgent agent;
    @Autowired private VacationCreationService creationService;
    @Autowired private VacationDeletionService deletionService;
    @Autowired private ReportGenerator reportGenerator;
    @Autowired private AppState appState;
    @Autowired private PlannerExcelReader reader;
    @Autowired private WorkingExcelWriter writer;
    @Autowired private VacationTypeService typeService;
    @Autowired private AuditService auditService;
    @Autowired private SyncService syncService;
    @Autowired private SlackNotificationService slackNotificationService;

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> body, HttpSession session) {
        String message = body.get("message");
        if (message == null || message.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(err("Empty message"));
        }
        String sessionId = (String) session.getAttribute("session_id");
        try {
            List<LeaveRecord> allRecords = loadMasterRecords();
            List<String> employeeNames = reader.getEmployeeNames(allRecords);

            PendingVacation pending = appState.getPendingVacation(sessionId);
            if (pending != null && "delete".equals(pending.getWizardType())) {
                return handleDeleteWizard(pending, message, sessionId, allRecords, employeeNames, session);
            }
            if (pending != null && "add".equals(pending.getWizardType())) {
                return handleAddWizard(pending, message, sessionId, allRecords, employeeNames, session);
            }
            if (agent.isAddVacationIntent(message)) {
                String sessionRole = (String) session.getAttribute("role");
                String sessionEmp  = (String) session.getAttribute("employee_name");
                // For employee role: if the message names a different employee, reject
                // immediately with a clear authorization message before the wizard starts.
                if ("employee".equals(sessionRole) && sessionEmp != null) {
                    String mentionedEmp = agent.resolveEmployeeName(message, allRecords);
                    if (mentionedEmp != null && !mentionedEmp.equalsIgnoreCase(sessionEmp)) {
                        return ResponseEntity.ok(reply(
                            "Cannot add: **" + mentionedEmp + "** can only modify their own vacation details. Request aborted.",
                            "text"));
                    }
                }
                PendingVacation pv = new PendingVacation("add");
                // Pre-seed the employee name so the wizard skips the "Which employee?" step
                // and goes straight to leave-type selection.
                if ("employee".equals(sessionRole) && sessionEmp != null) {
                    pv.setEmployeeName(sessionEmp);
                    pv.setState(PendingVacation.WizardState.NEED_TYPE);
                }
                VacationCreationService.WizardResult result = creationService.process(pv, message, employeeNames);
                if (result.cancelled()) appState.removePendingVacation(sessionId);
                else if (!result.confirmed()) appState.setPendingVacation(sessionId, pv);
                return ResponseEntity.ok(reply(result.reply(), result.type()));
            }
            if (agent.isDeleteVacationIntent(message)) {
                String sessionRole = (String) session.getAttribute("role");
                String sessionEmp  = (String) session.getAttribute("employee_name");
                // For employee role: if the message names a different employee, reject
                // immediately with a clear authorization message before the wizard starts.
                if ("employee".equals(sessionRole) && sessionEmp != null) {
                    String mentionedEmp = agent.resolveEmployeeName(message, allRecords);
                    if (mentionedEmp != null && !mentionedEmp.equalsIgnoreCase(sessionEmp)) {
                        return ResponseEntity.ok(reply(
                            "Cannot delete: **" + mentionedEmp + "** can only modify their own vacation details. Deletion aborted.",
                            "text"));
                    }
                }
                PendingVacation pv = new PendingVacation("delete");
                // Pre-seed the employee name and skip to date entry.
                if ("employee".equals(sessionRole) && sessionEmp != null) {
                    pv.setEmployeeName(sessionEmp);
                    pv.setState(PendingVacation.WizardState.DELETE_NEED_START);
                }
                VacationDeletionService.WizardResult result = deletionService.process(pv, message, employeeNames, allRecords);
                if (result.cancelled()) appState.removePendingVacation(sessionId);
                else if (!result.confirmed()) appState.setPendingVacation(sessionId, pv);
                return ResponseEntity.ok(reply(result.reply(), result.type()));
            }
            if (agent.isReportIntent(message)) {
                String empName = agent.resolveEmployeeName(message, allRecords);
                // Pronoun / contextual reference ("for her", "for him") — fall back to history
                if (empName == null) {
                    empName = agent.resolveEmployeeNameFromHistory(
                            appState.getConversationHistory(), allRecords);
                }
                if (empName != null && !allRecords.isEmpty()) {
                    int year = allRecords.stream().mapToInt(LeaveRecord::year).max().orElse(java.time.LocalDate.now().getYear());
                    String path = reportGenerator.generate(allRecords, empName, year);
                    return ResponseEntity.ok(reply("Report generated.\nreport-file: " + path, "report"));
                }
                // Name still unresolved — ask rather than falling through to the LLM
                return ResponseEntity.ok(reply(
                        "Which employee should I generate the report for? Please provide their name.", "text"));
            }
            String replyText = agent.ask(message, allRecords);
            return ResponseEntity.ok(reply(replyText, "text"));

        } catch (com.holidayleave.assistant.llm.OpenAIAdapter.LLMServiceException e) {
            return ResponseEntity.status(502).body(err(e.getMessage()));
        } catch (Exception e) {
            log.error("Chat error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(err("Internal server error"));
        }
    }

    @PostMapping("/clear-history")
    public ResponseEntity<Map<String, Object>> clearHistory() {
        appState.clearHistory();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("message", "Conversation history cleared.");
        return ResponseEntity.ok(r);
    }

    /** Debug endpoint: returns the raw context JSON for a question without calling the LLM.
     *  Also returns the current conversation history so poisoning can be diagnosed.
     *  Usage: POST /api/debug/context  body: {"message":"How many leaves does Dayananda have from January to March?"} */
    @PostMapping("/debug/context")
    public ResponseEntity<Map<String, Object>> debugContext(@RequestBody Map<String, String> body) {
        try {
            String message = body.getOrDefault("message", "");
            List<LeaveRecord> allRecords = loadMasterRecords();
            String contextJson = agent.buildContext(message, allRecords);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("question", message);
            r.put("context_json", contextJson);
            r.put("history_size", appState.getConversationHistory().size());
            r.put("history", appState.getConversationHistory());
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(err("Debug error: " + e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> handleAddWizard(
            PendingVacation pending, String message, String sessionId,
            List<LeaveRecord> allRecords, List<String> employeeNames,
            HttpSession session) throws IOException {
        VacationCreationService.WizardResult result = creationService.process(pending, message, employeeNames);
        if (result.confirmed()) {
            // ── Belt-and-suspenders ownership check before write ──────────────
            String sessionRole = (String) session.getAttribute("role");
            String sessionEmp  = (String) session.getAttribute("employee_name");
            if ("employee".equals(sessionRole)) {
                if (sessionEmp == null || !sessionEmp.equalsIgnoreCase(pending.getEmployeeName())) {
                    appState.removePendingVacation(sessionId);
                    return ResponseEntity.status(403).body(err(
                            "You are not permitted to add vacations for other employees."));
                }
            }
            VacationType vtype = typeService.findByCode(pending.getLeaveCode())
                    .orElseThrow(() -> new IllegalArgumentException("Leave type not found"));
            LeaveRecord record = new LeaveRecord(
                pending.getEmployeeName(), pending.getStartDate(), pending.getEndDate(),
                pending.getDays(), pending.getLeaveType(), pending.getReason());
            String workingPath = getWorkingPath(record.year());
            ensureWorkingCopy(workingPath, record.year());
            try {
                int cells = writer.addVacation(workingPath, record, vtype);
                // Eager cache eviction — master not yet updated by sync-daemon but evicting
                // now ensures the next read re-parses from disk (consistent with master).
                reader.evict(getMasterPath(record.year()));
                String actingUser = (String) session.getAttribute("username");
                if (actingUser == null) actingUser = "admin";
                auditService.log("vacation_added", actingUser, record.employeeName(),
                    "Added " + record.days() + "d [" + record.leaveType() + "] via chat", "success", "chat");
                syncService.triggerSync();
                slackNotificationService.notifyPcVacationAdded(record, pending.getLeaveCode(), actingUser);
                appState.removePendingVacation(sessionId);
                return ResponseEntity.ok(reply(
                    "Vacation added for **" + record.employeeName() + "**: " + record.leaveType() +
                    " " + record.startDate().format(FMT) + " to " + record.endDate().format(FMT) + " (" + cells + " days).", "text"));
            } catch (WorkingExcelWriter.ExcelWriteConflictException e) {
                String actingUser = (String) session.getAttribute("username");
                if (actingUser == null) actingUser = "admin";
                auditService.log("vacation_conflict", actingUser, record.employeeName(),
                    e.getMessage(), "error", "chat");
                appState.removePendingVacation(sessionId);
                return ResponseEntity.ok(reply(
                    "Could not add vacation: **" + record.employeeName() + "** already has leave scheduled on **"
                    + e.getConflictDate().format(FMT) + "** (code: " + e.getExistingCode() + "). "
                    + "Please choose a different date range.", "text"));
            }
        }
        if (result.cancelled()) appState.removePendingVacation(sessionId);
        return ResponseEntity.ok(reply(result.reply(), result.type()));
    }

    private ResponseEntity<Map<String, Object>> handleDeleteWizard(
            PendingVacation pending, String message, String sessionId,
            List<LeaveRecord> allRecords, List<String> employeeNames,
            HttpSession session) throws IOException {
        VacationDeletionService.WizardResult result = deletionService.process(pending, message, employeeNames, allRecords);
        if (result.confirmed()) {
            // ── Belt-and-suspenders ownership check before write ──────────────
            String sessionRole = (String) session.getAttribute("role");
            String sessionEmp  = (String) session.getAttribute("employee_name");
            if ("employee".equals(sessionRole)) {
                if (sessionEmp == null || !sessionEmp.equalsIgnoreCase(pending.getEmployeeName())) {
                    appState.removePendingVacation(sessionId);
                    return ResponseEntity.status(403).body(err(
                            "You are not permitted to delete another employee's vacation."));
                }
            }
            String workingPath = getWorkingPath(pending.getStartDate().getYear());
            ensureWorkingCopy(workingPath, pending.getStartDate().getYear());
            int cleared = writer.deleteVacation(workingPath, pending.getEmployeeName(),
                    pending.getStartDate(), pending.getEndDate());
            // Eager cache eviction after confirmed delete.
            reader.evict(getMasterPath(pending.getStartDate().getYear()));
            String actingUser = (String) session.getAttribute("username");
            if (actingUser == null) actingUser = "admin";
            auditService.log("vacation_deleted", actingUser, pending.getEmployeeName(),
                "Deleted vacation via chat", "success", "chat");
            syncService.triggerSync();
            String deletedLeaveType = resolveLeaveTypeForDelete(allRecords, pending);
            slackNotificationService.notifyVacationDeleted(pending, deletedLeaveType, actingUser);
            appState.removePendingVacation(sessionId);
            return ResponseEntity.ok(reply(
                "Vacation deleted for **" + pending.getEmployeeName() + "** (" + cleared + " cells cleared).", "text"));
        }
        if (result.cancelled()) appState.removePendingVacation(sessionId);
        return ResponseEntity.ok(reply(result.reply(), result.type()));
    }

    private List<LeaveRecord> loadMasterRecords() throws IOException {
        List<LeaveRecord> all = new ArrayList<>();
        for (String path : appState.getLoadedFiles()) {
            if (new File(path).exists()) all.addAll(reader.load(path));
        }
        return all;
    }

    private String getWorkingPath(int year) {
        return appState.getWorkingDir() + "/eIndkomst vacation " + year + ".xlsx";
    }

    private String getMasterPath(int year) {
        return appState.getDataDir() + "/eIndkomst vacation " + year + ".xlsx";
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

    private String resolveLeaveTypeForDelete(List<LeaveRecord> records, PendingVacation pending) {
        for (LeaveRecord r : records) {
            if (!r.employeeName().equalsIgnoreCase(pending.getEmployeeName())) continue;
            // Match any record whose date range overlaps the deleted range
            if (!r.endDate().isBefore(pending.getStartDate())
                    && !r.startDate().isAfter(pending.getEndDate())) {
                return r.leaveType();
            }
        }
        return "Unknown";
    }

    private Map<String, Object> reply(String text, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reply", text);
        m.put("type", type);
        return m;
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}
