package com.holidayleave.assistant.service;

import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.PendingVacation;
import com.holidayleave.assistant.model.PendingVacation.WizardState;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class VacationDeletionService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    public WizardResult process(PendingVacation pending, String message,
                                List<String> allEmployeeNames, List<LeaveRecord> allRecords) {
        WizardState state = pending.getState();
        if (state == WizardState.DELETE_IDLE)       return handleIdle(pending, message, allEmployeeNames);
        if (state == WizardState.DELETE_NEED_EMP)   return handleNeedEmp(pending, message, allEmployeeNames);
        if (state == WizardState.DELETE_NEED_START) return handleNeedStart(pending, message);
        if (state == WizardState.DELETE_NEED_END)   return handleNeedEnd(pending, message, allRecords);
        if (state == WizardState.DELETE_CONFIRM)    return handleConfirm(pending, message);
        return new WizardResult("Unexpected state. Type 'cancel' to abort.", "vacation_prompt", false, false);
    }

    private WizardResult handleIdle(PendingVacation p, String msg, List<String> names) {
        String emp = resolveEmployee(msg, names);
        if (emp != null) {
            p.setEmployeeName(emp);
            p.setState(WizardState.DELETE_NEED_START);
            return new WizardResult("Deleting leave for **" + emp + "**.\nWhat is the start date of the vacation to delete? (YYYY-MM-DD)",
                "vacation_prompt", false, false);
        }
        p.setState(WizardState.DELETE_NEED_EMP);
        return new WizardResult("Which employee's vacation should I delete?\nKnown employees: " + String.join(", ", names),
            "vacation_prompt", false, false);
    }

    private WizardResult handleNeedEmp(PendingVacation p, String msg, List<String> names) {
        if (isCancelled(msg)) return cancel(p);
        String emp = resolveEmployee(msg, names);
        if (emp == null) return new WizardResult("Employee not found. Known employees: " + String.join(", ", names),
            "vacation_prompt", false, false);
        p.setEmployeeName(emp);
        p.setState(WizardState.DELETE_NEED_START);
        return new WizardResult("Deleting leave for **" + emp + "**.\nStart date of the vacation to delete? (YYYY-MM-DD)",
            "vacation_prompt", false, false);
    }

    private WizardResult handleNeedStart(PendingVacation p, String msg) {
        if (isCancelled(msg)) return cancel(p);
        LocalDate date = parseDate(msg);
        if (date == null) return new WizardResult("Please enter a valid start date in YYYY-MM-DD format.",
            "vacation_prompt", false, false);
        p.setStartDate(date);
        p.setState(WizardState.DELETE_NEED_END);
        return new WizardResult("Start date: **" + date.format(FMT) + "**. What is the end date? (YYYY-MM-DD, or same date for single day)",
            "vacation_prompt", false, false);
    }

    private WizardResult handleNeedEnd(PendingVacation p, String msg, List<LeaveRecord> allRecords) {
        if (isCancelled(msg)) return cancel(p);
        LocalDate date = parseDate(msg);
        if (date == null) return new WizardResult("Please enter a valid end date in YYYY-MM-DD format.",
            "vacation_prompt", false, false);
        if (date.isBefore(p.getStartDate())) return new WizardResult(
            "End date must be on or after start date (" + p.getStartDate().format(FMT) + ").", "vacation_prompt", false, false);
        p.setEndDate(date);

        Set<LocalDate> coveredDays = new HashSet<>();
        for (LeaveRecord r : allRecords) {
            if (!r.employeeName().equalsIgnoreCase(p.getEmployeeName())) continue;
            for (LocalDate d = r.startDate(); !d.isAfter(r.endDate()); d = d.plusDays(1)) {
                if (d.getDayOfWeek().getValue() <= 5) coveredDays.add(d);
            }
        }

        List<LocalDate> requestedWorkingDays = new ArrayList<>();
        for (LocalDate d = p.getStartDate(); !d.isAfter(date); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) requestedWorkingDays.add(d);
        }

        for (LocalDate d : requestedWorkingDays) {
            if (!coveredDays.contains(d)) {
                p.setState(WizardState.DELETE_CANCELLED);
                return new WizardResult(
                    "Cannot delete: working day **" + d.format(FMT) + "** is not covered by any vacation for " +
                    p.getEmployeeName() + ". Deletion aborted.", "text", false, true);
            }
        }

        long days = requestedWorkingDays.size();
        p.setDays(days);
        p.setState(WizardState.DELETE_CONFIRM);

        String warning = "";
        if (p.getStartDate().equals(date)) {
            for (LeaveRecord r : allRecords) {
                if (!r.employeeName().equalsIgnoreCase(p.getEmployeeName())) continue;
                if (r.startDate().isBefore(p.getStartDate()) && !r.endDate().isBefore(date)) {
                    warning = "\n\nNote: This day is part of a longer block (" + r.startDate().format(FMT) + " to " +
                              r.endDate().format(FMT) + "). Only this single day will be cleared.";
                    break;
                }
            }
        }

        return new WizardResult(
            "Please confirm deletion:\n" +
            "* Employee: **" + p.getEmployeeName() + "**\n" +
            "* Dates: **" + p.getStartDate().format(FMT) + "** to **" + date.format(FMT) + "** (" + days + " working day" + (days != 1 ? "s" : "") + ")" +
            warning + "\n\nType **yes** to delete or **no** to cancel.",
            "vacation_prompt", false, false);
    }

    private WizardResult handleConfirm(PendingVacation p, String msg) {
        String lower = msg.toLowerCase().trim();
        if (lower.startsWith("yes") || lower.equals("y") || lower.equals("confirm")) {
            p.setState(WizardState.DELETED);
            return new WizardResult("", "vacation_prompt", true, false);
        }
        return cancel(p);
    }

    private WizardResult cancel(PendingVacation p) {
        p.setState(WizardState.DELETE_CANCELLED);
        return new WizardResult("Deletion cancelled.", "text", false, true);
    }

    private boolean isCancelled(String msg) {
        String l = msg.toLowerCase().trim();
        return l.equals("cancel") || l.equals("abort") || l.equals("stop") || l.equals("quit");
    }

    private String resolveEmployee(String message, List<String> names) {
        String lower = message.toLowerCase();
        for (String name : names) {
            if (lower.contains(name.toLowerCase())) return name;
        }
        String[] words = message.replaceAll("[^a-zA-Z ]", " ").toLowerCase().split("\\s+");
        Set<String> wordSet = new HashSet<>(Arrays.asList(words));
        for (String name : names) {
            String[] tokens = name.split("[^a-zA-Z]+");
            for (int i = 0; i < tokens.length; i++) {
                String tok = tokens[i].toLowerCase();
                int min = (i == 0) ? 3 : 4;
                if (tok.length() >= min && wordSet.contains(tok)) return name;
            }
        }
        int best = 0; String bestName = null;
        for (String name : names) {
            int score = 0;
            for (String tok : name.split("[^a-zA-Z]+")) {
                if (tok.length() >= 4 && wordSet.contains(tok.toLowerCase())) score++;
            }
            if (score > best) { best = score; bestName = name; }
        }
        return best >= 1 ? bestName : null;
    }

    private LocalDate parseDate(String s) {
        try { return LocalDate.parse(s.trim()); } catch (DateTimeParseException e) { return null; }
    }

    public static final class WizardResult {
        private final String reply;
        private final String type;
        private final boolean confirmed;
        private final boolean cancelled;

        public WizardResult(String reply, String type, boolean confirmed, boolean cancelled) {
            this.reply     = reply;
            this.type      = type;
            this.confirmed = confirmed;
            this.cancelled = cancelled;
        }

        public String reply()      { return reply; }
        public String type()       { return type; }
        public boolean confirmed() { return confirmed; }
        public boolean cancelled() { return cancelled; }
    }
}
