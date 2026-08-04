package com.holidayleave.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holidayleave.assistant.analysis.LeaveAnalysisService;
import com.holidayleave.assistant.llm.LLMService;
import com.holidayleave.assistant.model.LeaveAnalysisResult;
import com.holidayleave.assistant.model.LeaveRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class HolidayAgent {

    private static final Logger log = LoggerFactory.getLogger(HolidayAgent.class);

    private static final List<String> ADD_KEYWORDS;
    private static final List<String> DELETE_KEYWORDS;
    private static final List<String> REPORT_KEYWORDS;
    private static final List<String> ALL_EMPLOYEES_KEYWORDS;

    static {
        ADD_KEYWORDS = Arrays.asList(
            "add vacation","add leave","add holiday",
            "book vacation","book leave","book holiday",
            "request leave","request vacation","request holiday",
            "new vacation","new leave","create vacation","create leave",
            "record vacation","record leave","log vacation","log leave",
            "schedule vacation","schedule leave"
        );
        DELETE_KEYWORDS = Arrays.asList(
            "delete vacation","delete leave","delete holiday",
            "remove vacation","remove leave","remove holiday",
            "cancel vacation","cancel leave",
            "undo vacation","undo leave",
            "erase vacation","erase leave"
        );
        REPORT_KEYWORDS = Arrays.asList(
            "generate report","create report","make report",
            "yearly report","annual report","leave report",
            "generate leave","create leave","export report",
            "html report","produce report"
        );
        ALL_EMPLOYEES_KEYWORDS = Arrays.asList(
            "all employee","all staff","everyone","all workers"
        );
    }

    private static final Pattern MONTH_PATTERN = Pattern.compile(
        "\\b(january|february|march|april|may|june|july|august|september|october|november|december|" +
        "jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)\\b", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");

    @Autowired private LLMService llmService;
    @Autowired private LeaveAnalysisService analysisService;
    @Autowired private AppState appState;

    private final ObjectMapper mapper = new ObjectMapper();

    public String ask(String question, List<LeaveRecord> allRecords) {
        String lower = question.toLowerCase();
        int year = extractYear(question, allRecords);
        boolean allEmployees = isAllEmployeesQuery(lower);
        String employeeName = allEmployees ? null : resolveEmployeeName(question, allRecords);
        // Pronoun reference ("she", "he", "her", "him") — primary pass returns null.
        // Fall back to history before choosing the context builder so the LLM receives
        // real leave data instead of a sparse Shape C context.
        if (!allEmployees && employeeName == null) {
            employeeName = resolveEmployeeNameFromHistory(
                    appState.getConversationHistory(), allRecords);
        }

        String context;
        if (allEmployees) {
            context = buildContextForAll(allRecords, year);
        } else if (employeeName != null) {
            context = buildContextForEmployee(allRecords, employeeName, year, question);
        } else {
            context = buildGenericContext(allRecords, year);
        }

        String reply = llmService.ask(null, context, question, appState.getConversationHistory());
        appState.addToHistory(question, reply);
        return reply;
    }

    public boolean isAddVacationIntent(String message) {
        String lower = message.toLowerCase();
        for (String kw : ADD_KEYWORDS) { if (lower.contains(kw)) return true; }
        return false;
    }

    public boolean isDeleteVacationIntent(String message) {
        String lower = message.toLowerCase();
        for (String kw : DELETE_KEYWORDS) { if (lower.contains(kw)) return true; }
        return false;
    }

    public boolean isReportIntent(String message) {
        String lower = message.toLowerCase();
        for (String kw : REPORT_KEYWORDS) { if (lower.contains(kw)) return true; }
        return false;
    }

    public boolean isAllEmployeesQuery(String message) {
        String lower = message.toLowerCase();
        for (String kw : ALL_EMPLOYEES_KEYWORDS) { if (lower.contains(kw)) return true; }
        return false;
    }

    /**
     * Scans conversation history in reverse order and returns the first employee
     * name that can be resolved from any prior message (user or assistant).
     * Delegates to the existing resolveEmployeeName logic so matching rules stay consistent.
     */
    public String resolveEmployeeNameFromHistory(List<Map<String, String>> history,
                                                  List<LeaveRecord> allRecords) {
        if (history == null || history.isEmpty()) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            String content = history.get(i).get("content");
            if (content == null || content.isEmpty()) continue;
            String found = resolveEmployeeName(content, allRecords);
            if (found != null) return found;
        }
        return null;
    }

    public String resolveEmployeeName(String question, List<LeaveRecord> allRecords) {
        List<String> names = new ArrayList<>();
        for (LeaveRecord r : allRecords) {
            if (!names.contains(r.employeeName())) names.add(r.employeeName());
        }
        String lower = question.toLowerCase();
        // Pass 1
        for (String name : names) {
            if (lower.contains(name.toLowerCase())) return name;
        }
        // Pass 2
        String cleaned = question.replaceAll("[^a-zA-Z ]", " ").toLowerCase();
        Set<String> questionWordSet = new HashSet<>(Arrays.asList(cleaned.split("\\s+")));
        for (String name : names) {
            String[] tokens = name.split("[^a-zA-Z]+");
            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i].toLowerCase();
                int minLen = (i == 0) ? 3 : 4;
                if (token.length() >= minLen && questionWordSet.contains(token)) return name;
            }
        }
        // Pass 3
        int bestScore = 0; String bestName = null;
        for (String name : names) {
            int score = 0;
            for (String tok : name.split("[^a-zA-Z]+")) {
                if (tok.length() >= 4 && questionWordSet.contains(tok.toLowerCase())) score++;
            }
            if (score > bestScore) { bestScore = score; bestName = name; }
        }
        return bestScore >= 1 ? bestName : null;
    }

    private String buildContextForEmployee(List<LeaveRecord> allRecords, String employeeName,
                                            int year, String question) {
        List<LeaveRecord> empRecords = new ArrayList<>();
        for (LeaveRecord r : allRecords) {
            if (r.employeeName().equalsIgnoreCase(employeeName) && r.year() == year) empRecords.add(r);
        }

        LeaveAnalysisResult analysis = analysisService.analyse(allRecords, employeeName, year);
        double consumed = analysisService.consumedToDate(empRecords);

        Integer requestedMonth = extractMonth(question);
        Map<Integer, Double> byMonth = analysis.byMonth();
        Map<String, Double> byType  = analysis.byType();

        if (requestedMonth != null) {
            Map<Integer, Double> filteredByMonth = new LinkedHashMap<>();
            filteredByMonth.put(requestedMonth, byMonth.containsKey(requestedMonth) ? byMonth.get(requestedMonth) : 0.0);
            byMonth = filteredByMonth;
            final int rm = requestedMonth;
            Map<String, Double> filteredByType = new LinkedHashMap<>();
            for (LeaveRecord r : empRecords) {
                if (r.startDate().getMonthValue() == rm || r.endDate().getMonthValue() == rm) {
                    Double existing = filteredByType.get(r.leaveType());
                    filteredByType.put(r.leaveType(), (existing != null ? existing : 0.0) + r.days());
                }
            }
            byType = filteredByType;
        }

        // When a specific month was requested, only include records that overlap that month.
        // This keeps leave_records consistent with the already-filtered by_month/by_type.
        List<LeaveRecord> recordsForContext = empRecords;
        if (requestedMonth != null) {
            final int rm = requestedMonth;
            recordsForContext = new ArrayList<>();
            for (LeaveRecord r : empRecords) {
                if (r.startDate().getMonthValue() == rm || r.endDate().getMonthValue() == rm) {
                    recordsForContext.add(r);
                }
            }
        }

        List<Map<String, Object>> recordMaps = new ArrayList<>();
        for (LeaveRecord r : recordsForContext) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start_date",  r.startDate().toString());
            m.put("end_date",    r.endDate().toString());
            m.put("days",        r.days());
            m.put("leave_type",  r.leaveType());
            m.put("reason",      r.reason());
            m.put("year",        r.year());
            m.put("month",       r.startDate().getMonthValue());
            m.put("month_name",  r.startDate().getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
            recordMaps.add(m);
        }

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("employee_name",       employeeName);
        ctx.put("analysis_year",       year);
        ctx.put("today",               LocalDate.now().toString());
        ctx.put("entitlement_days",    analysis.entitlement());
        ctx.put("consumed_days",       consumed);
        ctx.put("remaining_days",      analysis.remaining());
        ctx.put("utilization_pct",     analysis.utilizationPct());
        ctx.put("avg_days_per_month",  analysis.avgPerMonth());
        ctx.put("longest_streak_days", analysis.longestStreak());
        ctx.put("by_month",            byMonth);
        ctx.put("by_type",             byType);
        Map<String, Double> byYear = new LinkedHashMap<>();
        byYear.put(String.valueOf(year), analysis.entitlement());
        ctx.put("by_year",             byYear);
        ctx.put("leave_records",       recordMaps);
        ctx.put("total_records_shown", recordMaps.size());
        if (requestedMonth != null) {
            ctx.put("days_in_requested_month", byMonth.containsKey(requestedMonth) ? byMonth.get(requestedMonth) : 0.0);
        }

        try { return mapper.writeValueAsString(ctx); } catch (Exception e) { return "{}"; }
    }

    private String buildContextForAll(List<LeaveRecord> allRecords, int year) {
        List<LeaveRecord> yearRecords = new ArrayList<>();
        for (LeaveRecord r : allRecords) { if (r.year() == year) yearRecords.add(r); }

        Map<String, Double> summaryByEmployee = new LinkedHashMap<>();
        for (LeaveRecord r : yearRecords) {
            Double existing = summaryByEmployee.get(r.employeeName());
            summaryByEmployee.put(r.employeeName(), (existing != null ? existing : 0.0) + r.days());
        }

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("analysis_year", year);
        ctx.put("today", LocalDate.now().toString());
        ctx.put("all_employees_summary", summaryByEmployee);
        ctx.put("total_employees", summaryByEmployee.size());
        ctx.put("total_records", yearRecords.size());
        try { return mapper.writeValueAsString(ctx); } catch (Exception e) { return "{}"; }
    }

    private String buildGenericContext(List<LeaveRecord> allRecords, int year) {
        List<String> employees = new ArrayList<>();
        for (LeaveRecord r : allRecords) { if (!employees.contains(r.employeeName())) employees.add(r.employeeName()); }
        Set<Integer> yearsSet = new LinkedHashSet<>();
        for (LeaveRecord r : allRecords) yearsSet.add(r.year());
        List<Integer> years = new ArrayList<>(yearsSet);
        Collections.sort(years);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("analysis_year", year);
        ctx.put("today", LocalDate.now().toString());
        ctx.put("employees", employees);
        ctx.put("total_employees", employees.size());
        ctx.put("available_years", years);
        try { return mapper.writeValueAsString(ctx); } catch (Exception e) { return "{}"; }
    }

    private int extractYear(String question, List<LeaveRecord> allRecords) {
        Matcher m = YEAR_PATTERN.matcher(question);
        if (m.find()) return Integer.parseInt(m.group(1));
        int max = LocalDate.now().getYear();
        for (LeaveRecord r : allRecords) { if (r.year() > max) max = r.year(); }
        return max;
    }

    private Integer extractMonth(String question) {
        Matcher m = MONTH_PATTERN.matcher(question.toLowerCase());
        if (!m.find()) return null;
        return parseMonthName(m.group(1));
    }

    private int parseMonthName(String name) {
        switch (name.toLowerCase()) {
            case "jan": case "january":   return 1;
            case "feb": case "february":  return 2;
            case "mar": case "march":     return 3;
            case "apr": case "april":     return 4;
            case "may":                   return 5;
            case "jun": case "june":      return 6;
            case "jul": case "july":      return 7;
            case "aug": case "august":    return 8;
            case "sep": case "september": return 9;
            case "oct": case "october":   return 10;
            case "nov": case "november":  return 11;
            case "dec": case "december":  return 12;
            default: return 0;
        }
    }
}
