package com.holidayleave.assistant.service;

import com.holidayleave.assistant.model.PendingVacation;
import com.holidayleave.assistant.model.PendingVacation.WizardState;
import com.holidayleave.assistant.model.VacationType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class VacationCreationService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    @Autowired
    private VacationTypeService vacationTypeService;

    @Autowired
    private RestrictedVacationTypeService restrictedVacationTypeService;

    public WizardResult process(PendingVacation pending, String message, List<String> allEmployeeNames) {
        WizardState state = pending.getState();
        if (state == WizardState.IDLE)        return handleIdle(pending, message, allEmployeeNames);
        if (state == WizardState.NEED_EMP)    return handleNeedEmp(pending, message, allEmployeeNames);
        if (state == WizardState.NEED_TYPE)   return handleNeedType(pending, message);
        if (state == WizardState.NEED_START)  return handleNeedStart(pending, message);
        if (state == WizardState.NEED_END)    return handleNeedEnd(pending, message);
        if (state == WizardState.CONFIRM)     return handleConfirm(pending, message);
        return new WizardResult("Unexpected wizard state. Type 'cancel' to abort.", "vacation_prompt", false, false);
    }

    private WizardResult handleIdle(PendingVacation p, String msg, List<String> names) {
        String emp = resolveEmployee(msg, names);
        if (emp != null) {
            p.setEmployeeName(emp);
            p.setState(WizardState.NEED_TYPE);
            return new WizardResult(
                "Got it! Adding leave for **" + emp + "**.\nWhat type of leave? Available: " + typeList(),
                "vacation_prompt", false, false);
        }
        p.setState(WizardState.NEED_EMP);
        return new WizardResult(
            "Which employee should I add the vacation for?\nKnown employees: " + String.join(", ", names),
            "vacation_prompt", false, false);
    }

    private WizardResult handleNeedEmp(PendingVacation p, String msg, List<String> names) {
        if (isCancelled(msg)) return cancel(p);
        String emp = resolveEmployee(msg, names);
        if (emp == null) {
            return new WizardResult(
                "I couldn't find that employee. Known employees: " + String.join(", ", names),
                "vacation_prompt", false, false);
        }
        p.setEmployeeName(emp);
        p.setState(WizardState.NEED_TYPE);
        return new WizardResult("Got it — **" + emp + "**.\nWhat type of leave? Available: " + typeList(),
            "vacation_prompt", false, false);
    }

    private WizardResult handleNeedType(PendingVacation p, String msg) {
        if (isCancelled(msg)) return cancel(p);
        String lower = msg.toLowerCase().trim();
        VacationType found = null;

        // Pass 1 — exact code match (highest priority: "E", "PC", "V" …)
        for (VacationType t : vacationTypeService.findAll()) {
            if (t.code().equalsIgnoreCase(lower)) { found = t; break; }
        }
        // Pass 2 — exact label match ("education", "vacation" …)
        if (found == null) {
            for (VacationType t : vacationTypeService.findAll()) {
                if (t.label().equalsIgnoreCase(lower)) { found = t; break; }
            }
        }
        // Pass 3 — label substring, only for inputs of 3+ chars to prevent
        // single-character noise matching (e.g. "e" hitting "Personal Choice Holiday")
        if (found == null && lower.length() >= 3) {
            for (VacationType t : vacationTypeService.findAll()) {
                if (t.label().toLowerCase().contains(lower)) { found = t; break; }
            }
        }

        if (found == null) {
            return new WizardResult("I don't recognise that leave type. Available: " + typeList(),
                "vacation_prompt", false, false);
        }
        // Reject restricted types in the wizard
        if (restrictedVacationTypeService.isRestricted(found.code())) {
            return new WizardResult(
                "The vacation type **" + found.label() + "** is currently disabled by the administrator " +
                "and cannot be requested at this time. Available: " + typeList(),
                "vacation_prompt", false, false);
        }
        p.setLeaveType(found.label());
        p.setLeaveCode(found.code());
        p.setState(WizardState.NEED_START);
        return new WizardResult("Leave type: **" + found.label() + "**.\nWhat is the start date? (YYYY-MM-DD)",
            "vacation_prompt", false, false);
    }

    private WizardResult handleNeedStart(PendingVacation p, String msg) {
        if (isCancelled(msg)) return cancel(p);
        LocalDate date = parseDate(msg);
        if (date == null) return new WizardResult("Please enter a valid start date in YYYY-MM-DD format.",
            "vacation_prompt", false, false);
        p.setStartDate(date);
        p.setState(WizardState.NEED_END);
        return new WizardResult("Start date: **" + date.format(FMT) + "**.\nWhat is the end date? (YYYY-MM-DD, must be >= start date)",
            "vacation_prompt", false, false);
    }

    private WizardResult handleNeedEnd(PendingVacation p, String msg) {
        if (isCancelled(msg)) return cancel(p);
        LocalDate date = parseDate(msg);
        if (date == null) return new WizardResult("Please enter a valid end date in YYYY-MM-DD format.",
            "vacation_prompt", false, false);
        if (date.isBefore(p.getStartDate())) return new WizardResult(
            "End date must be on or after start date (" + p.getStartDate().format(FMT) + ").", "vacation_prompt", false, false);
        p.setEndDate(date);
        long days = countWeekdays(p.getStartDate(), date);
        if (days == 0) {
            return new WizardResult(
                "The selected date range contains no working days (Mon\u2013Fri). " +
                "Please enter a date range that includes at least one working day.",
                "vacation_prompt", false, false);
        }
        p.setDays(days);
        p.setState(WizardState.CONFIRM);
        return new WizardResult(
            "Please confirm:\n" +
            "* Employee: **" + p.getEmployeeName() + "**\n" +
            "* Type: **" + p.getLeaveType() + "**\n" +
            "* Dates: **" + p.getStartDate().format(FMT) + "** to **" + date.format(FMT) + "** (" + days + " working day" + (days != 1 ? "s" : "") + ")\n\n" +
            "Type **yes** to save or **no** to cancel.",
            "vacation_prompt", false, false);
    }

    private WizardResult handleConfirm(PendingVacation p, String msg) {
        String lower = msg.toLowerCase().trim();
        if (lower.startsWith("yes") || lower.equals("y") || lower.equals("confirm")) {
            p.setState(WizardState.SAVED);
            return new WizardResult("", "vacation_prompt", true, false);
        }
        return cancel(p);
    }

    private WizardResult cancel(PendingVacation p) {
        p.setState(WizardState.CANCELLED);
        return new WizardResult("Vacation entry cancelled.", "text", false, true);
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

    private String typeList() {
        List<VacationType> types = vacationTypeService.findAll();
        List<String> restricted = restrictedVacationTypeService.getRestrictedTypes();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (VacationType t : types) {
            if (restricted.contains(t.code().toUpperCase())) continue; // skip restricted
            if (!first) sb.append(", ");
            sb.append(t.label()).append(" (").append(t.code()).append(")");
            first = false;
        }
        return sb.toString();
    }

    private LocalDate parseDate(String s) {
        try { return LocalDate.parse(s.trim()); } catch (DateTimeParseException e) { return null; }
    }

    public static long countWeekdays(LocalDate start, LocalDate end) {
        long count = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) count++;
        }
        return count;
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
