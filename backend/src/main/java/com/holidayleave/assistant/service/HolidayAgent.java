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

    // ── Date-query detection ──────────────────────────────────────────────────
    // Matches patterns such as: "15 March 2026", "March 15", "03/15/2026",
    // "2026-03-15", "March 2nd", "2nd March 2026", "March 2, 2026"
    private static final Pattern DATE_QUERY_KEYWORDS = Pattern.compile(
        "\\b(who is on leave|who is off|who will be|who has leave|who are on leave" +
        "|who are off|which employees|are any|is anyone|is .+ on leave|on leave on" +
        "|off on|vacation on|away on|absent on)\\b", Pattern.CASE_INSENSITIVE
    );
    // Matches a day number (with optional ordinal) paired with a month name, or numeric date formats
    private static final Pattern SPECIFIC_DATE_PATTERN = Pattern.compile(
        // dd Month yyyy  /  Month dd yyyy  /  Month dd, yyyy
        "(?:(\\d{1,2})(?:st|nd|rd|th)?\\s+" +
            "(january|february|march|april|may|june|july|august|september|october|november|december|" +
             "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)" +
            "(?:\\s+(20\\d{2}))?)" +
        "|" +
        "(?:(january|february|march|april|may|june|july|august|september|october|november|december|" +
              "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)" +
           "\\s+(\\d{1,2})(?:st|nd|rd|th)?" +
           "(?:,?\\s+(20\\d{2}))?)" +
        "|" +
        // yyyy-mm-dd
        "(?:(20\\d{2})-(\\d{2})-(\\d{2}))" +
        "|" +
        // dd/mm/yyyy  or  mm/dd/yyyy — treated as dd/mm/yyyy (European, matching regional convention)
        "(?:(\\d{2})/(\\d{2})/(20\\d{2}))",
        Pattern.CASE_INSENSITIVE
    );

    @Autowired private LLMService llmService;
    @Autowired private LeaveAnalysisService analysisService;
    @Autowired private AppState appState;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Returns the raw context JSON that would be sent to the LLM for the given question, without calling the LLM. */
    public String buildContext(String question, List<LeaveRecord> allRecords) {
        String lower = question.toLowerCase();
        int year = extractYear(question, allRecords);

        // Date-specific query ("who is on leave on 02 March 2026?") — checked before employee resolution
        LocalDate specificDate = extractSpecificDate(question, year);
        if (specificDate != null) return buildContextForDate(allRecords, specificDate);

        boolean allEmployees = isAllEmployeesQuery(lower);
        String employeeName = allEmployees ? null : resolveEmployeeName(question, allRecords);
        if (!allEmployees && employeeName == null) {
            employeeName = resolveEmployeeNameFromHistory(appState.getConversationHistory(), allRecords);
        }
        if (allEmployees) return buildContextForAll(allRecords, year);
        if (employeeName != null) return buildContextForEmployee(allRecords, employeeName, year, question);
        return buildGenericContext(allRecords, year);
    }

    public String ask(String question, List<LeaveRecord> allRecords) {
        String lower = question.toLowerCase();
        int year = extractYear(question, allRecords);

        // Date-specific query — bypass employee resolution and build a cross-employee date context
        LocalDate specificDate = extractSpecificDate(question, year);
        if (specificDate != null) {
            String context = buildContextForDate(allRecords, specificDate);
            String reply = llmService.ask(null, context, question, appState.getConversationHistory());
            appState.addToHistory(question, reply);
            return reply;
        }

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

        List<Integer> requestedMonths = extractMonths(question);
        Integer startMonth = requestedMonths.isEmpty() ? null : requestedMonths.get(0);
        Integer endMonth   = requestedMonths.size() > 1 ? requestedMonths.get(1) : null;

        // Normalise: ensure startMonth <= endMonth so "March to January" is treated as Jan–Mar.
        if (startMonth != null && endMonth != null && startMonth > endMonth) {
            Integer tmp = startMonth; startMonth = endMonth; endMonth = tmp;
        }

        Map<Integer, Double> byMonth = analysis.byMonth();
        Map<String, Double> byType  = analysis.byType();
        List<LeaveRecord> recordsForContext = empRecords;

        if (startMonth != null && endMonth != null) {
            // ── RANGE PATH: aggregate all months from startMonth through endMonth inclusive ──

            // 1. Collect every month number in the range.
            List<Integer> rangeMonths = new ArrayList<>();
            for (int m = startMonth; m <= endMonth; m++) rangeMonths.add(m);

            // 2. Build byMonth map for the range (all-types totals — used for total-leave questions).
            Map<Integer, Double> rangeByMonth = new LinkedHashMap<>();
            double rangeAllTypesTotal = 0.0;
            for (int m : rangeMonths) {
                double val = byMonth.containsKey(m) ? byMonth.get(m) : 0.0;
                rangeByMonth.put(m, val);
                rangeAllTypesTotal += val;
            }
            byMonth = rangeByMonth;

            // 3. Date window covering the full range.
            LocalDate rangeStart = LocalDate.of(year, startMonth, 1);
            LocalDate rangeEnd   = LocalDate.of(year, endMonth, 1)
                                       .withDayOfMonth(LocalDate.of(year, endMonth, 1).lengthOfMonth());

            // 4. Compute proportional byType and per-month-by-type across the entire range window.
            Map<String, Double> rangeByType = new LinkedHashMap<>();
            // byMonthByType: month-number → (leaveType → proportional days)
            Map<Integer, Map<String, Double>> byMonthByType = new LinkedHashMap<>();
            for (int m : rangeMonths) byMonthByType.put(m, new LinkedHashMap<>());
            List<LeaveRecord> rangeRecords = new ArrayList<>();
            for (LeaveRecord r : empRecords) {
                if (r.endDate().isBefore(rangeStart) || r.startDate().isAfter(rangeEnd)) continue;
                rangeRecords.add(r);
                LocalDate oStart = r.startDate().isBefore(rangeStart) ? rangeStart : r.startDate();
                LocalDate oEnd   = r.endDate().isAfter(rangeEnd)       ? rangeEnd   : r.endDate();
                long totalWd   = countWorkingDays(r.startDate(), r.endDate());
                long overlapWd = countWorkingDays(oStart, oEnd);
                double share = totalWd > 0 ? r.days() * ((double) overlapWd / totalWd) : 0.0;
                if (share > 0) {
                    Double existing = rangeByType.get(r.leaveType());
                    rangeByType.put(r.leaveType(), (existing != null ? existing : 0.0) + share);
                }
                // Per-month-by-type: distribute this record's days proportionally to each range month
                for (int m : rangeMonths) {
                    LocalDate mStart = LocalDate.of(year, m, 1);
                    LocalDate mEnd   = mStart.withDayOfMonth(mStart.lengthOfMonth());
                    LocalDate moStart = r.startDate().isBefore(mStart) ? mStart : r.startDate();
                    LocalDate moEnd   = r.endDate().isAfter(mEnd)      ? mEnd   : r.endDate();
                    if (moStart.isAfter(moEnd)) continue;
                    long mOverlapWd = countWorkingDays(moStart, moEnd);
                    double mShare = totalWd > 0 ? r.days() * ((double) mOverlapWd / totalWd) : 0.0;
                    if (mShare > 0) {
                        Map<String, Double> mTypeMap = byMonthByType.get(m);
                        Double mExisting = mTypeMap.get(r.leaveType());
                        mTypeMap.put(r.leaveType(), (mExisting != null ? mExisting : 0.0) + mShare);
                    }
                }
            }
            byType = rangeByType;
            recordsForContext = rangeRecords;

            // 5. Build record maps and context — range-specific fields.
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
            ctx.put("employee_name",                   employeeName);
            ctx.put("analysis_year",                   year);
            ctx.put("today",                           LocalDate.now().toString());
            ctx.put("entitlement_days",                analysis.entitlement());
            ctx.put("consumed_days",                   consumed);
            ctx.put("remaining_days",                  analysis.remaining());
            ctx.put("utilization_pct",                 analysis.utilizationPct());
            ctx.put("avg_days_per_month",              analysis.avgPerMonth());
            ctx.put("longest_streak_days",             analysis.longestStreak());
            ctx.put("by_month",                        byMonth);
            ctx.put("by_type",                         byType);
            ctx.put("by_month_by_type",                byMonthByType);
            Map<String, Double> byYear = new LinkedHashMap<>();
            byYear.put(String.valueOf(year), analysis.entitlement());
            ctx.put("by_year",                         byYear);
            ctx.put("leave_records",                   recordMaps);
            ctx.put("total_records_shown",             recordMaps.size());
            ctx.put("is_range_query",                  true);
            ctx.put("total_all_leave_types_in_range",  rangeAllTypesTotal);
            ctx.put("range_start_month",               startMonth);
            ctx.put("range_start_month_name",          java.time.Month.of(startMonth)
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH));
            ctx.put("range_end_month",                 endMonth);
            ctx.put("range_end_month_name",            java.time.Month.of(endMonth)
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH));

            try { return mapper.writeValueAsString(ctx); } catch (Exception e) { return "{}"; }

        } else if (startMonth != null) {
            // ── SINGLE-MONTH PATH: existing logic preserved exactly ──
            final int rm = startMonth;
            Map<Integer, Double> filteredByMonth = new LinkedHashMap<>();
            filteredByMonth.put(rm, byMonth.containsKey(rm) ? byMonth.get(rm) : 0.0);
            byMonth = filteredByMonth;

            LocalDate monthStart = LocalDate.of(year, rm, 1);
            LocalDate monthEnd   = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
            Map<String, Double> filteredByType = new LinkedHashMap<>();
            for (LeaveRecord r : empRecords) {
                LocalDate oStart = r.startDate().isBefore(monthStart) ? monthStart : r.startDate();
                LocalDate oEnd   = r.endDate().isAfter(monthEnd)      ? monthEnd   : r.endDate();
                if (oStart.isAfter(oEnd)) continue;
                long totalWd   = countWorkingDays(r.startDate(), r.endDate());
                long overlapWd = countWorkingDays(oStart, oEnd);
                double share = totalWd > 0 ? r.days() * ((double) overlapWd / totalWd) : 0.0;
                if (share > 0) {
                    Double existing = filteredByType.get(r.leaveType());
                    filteredByType.put(r.leaveType(), (existing != null ? existing : 0.0) + share);
                }
            }
            byType = filteredByType;

            recordsForContext = new ArrayList<>();
            for (LeaveRecord r : empRecords) {
                if (!r.endDate().isBefore(monthStart) && !r.startDate().isAfter(monthEnd)) {
                    recordsForContext.add(r);
                }
            }
        }

        // ── FULL-YEAR PATH and SINGLE-MONTH PATH share the same context serialisation ──
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
        if (startMonth != null) {
            ctx.put("total_all_leave_types_in_month", byMonth.containsKey(startMonth) ? byMonth.get(startMonth) : 0.0);
            ctx.put("context_scope_month",            startMonth);
            ctx.put("context_scope_month_name",       java.time.Month.of(startMonth)
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH));
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

    /**
     * Extracts up to two distinct month numbers from the question, in the order they appear.
     * Used to detect range queries such as "from March to April".
     * Preserves the original extractMonth() signature so existing callers and tests are unaffected.
     */
    private List<Integer> extractMonths(String question) {
        Matcher m = MONTH_PATTERN.matcher(question.toLowerCase());
        List<Integer> found = new ArrayList<>();
        while (m.find()) {
            int month = parseMonthName(m.group(1));
            if (month > 0 && !found.contains(month)) found.add(month);
            if (found.size() == 2) break;
        }
        return found;
    }

    /** Counts Mon–Fri days in the inclusive range [start, end]. */
    private long countWorkingDays(LocalDate start, LocalDate end) {
        long count = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) count++;
        }
        return count;
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

    /**
     * Attempts to extract a specific calendar date from the question.
     * Supports: "02 March 2026", "March 2nd 2026", "March 2, 2026",
     *           "2026-03-02", "02/03/2026" (dd/mm/yyyy).
     * Returns null if no specific date is found.
     * The fallback year is supplied by the caller (already extracted from the question or data).
     */
    LocalDate extractSpecificDate(String question, int fallbackYear) {
        Matcher m = SPECIFIC_DATE_PATTERN.matcher(question);
        if (!m.find()) return null;
        try {
            // Group layout (1-based):
            // Alt 1 (dd Month [yyyy]): g1=day, g2=monthName, g3=year
            // Alt 2 (Month dd [yyyy]): g4=monthName, g5=day, g6=year
            // Alt 3 (yyyy-mm-dd):      g7=year, g8=month, g9=day
            // Alt 4 (dd/mm/yyyy):      g10=day, g11=month, g12=year
            if (m.group(1) != null) {
                int day   = Integer.parseInt(m.group(1));
                int month = parseMonthName(m.group(2));
                int year  = m.group(3) != null ? Integer.parseInt(m.group(3)) : fallbackYear;
                return LocalDate.of(year, month, day);
            } else if (m.group(4) != null) {
                int month = parseMonthName(m.group(4));
                int day   = Integer.parseInt(m.group(5));
                int year  = m.group(6) != null ? Integer.parseInt(m.group(6)) : fallbackYear;
                return LocalDate.of(year, month, day);
            } else if (m.group(7) != null) {
                int year  = Integer.parseInt(m.group(7));
                int month = Integer.parseInt(m.group(8));
                int day   = Integer.parseInt(m.group(9));
                return LocalDate.of(year, month, day);
            } else if (m.group(10) != null) {
                int day   = Integer.parseInt(m.group(10));
                int month = Integer.parseInt(m.group(11));
                int year  = Integer.parseInt(m.group(12));
                return LocalDate.of(year, month, day);
            }
        } catch (Exception e) {
            log.debug("extractSpecificDate: could not parse date from '{}': {}", question, e.getMessage());
        }
        return null;
    }

    /**
     * Builds a DATE-QUERY context: scans all records and collects every employee
     * whose leave span covers the requested date (start_date ≤ date ≤ end_date, type ≠ A).
     * Returns a self-contained JSON context with:
     *   query_date, employees_on_leave (array with name/leave_type/start/end/reason),
     *   employees_not_on_leave_count, total_employees_checked.
     */
    String buildContextForDate(List<LeaveRecord> allRecords, LocalDate date) {
        List<Map<String, Object>> onLeave = new ArrayList<>();
        Set<String> allNames = new LinkedHashSet<>();
        for (LeaveRecord r : allRecords) {
            if (r.year() != date.getYear()) continue;
            allNames.add(r.employeeName());
        }
        for (LeaveRecord r : allRecords) {
            if (r.year() != date.getYear()) continue;
            if ("A".equalsIgnoreCase(r.leaveType())) continue;
            if (!r.startDate().isAfter(date) && !r.endDate().isBefore(date)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("employee_name", r.employeeName());
                entry.put("leave_type",    r.leaveType());
                entry.put("start_date",    r.startDate().toString());
                entry.put("end_date",      r.endDate().toString());
                entry.put("days",          r.days());
                entry.put("reason",        r.reason());
                onLeave.add(entry);
            }
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("query_type",                  "date_query");
        ctx.put("query_date",                  date.toString());
        ctx.put("query_date_display",          date.getDayOfMonth() + " " +
                date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + date.getYear());
        ctx.put("today",                       LocalDate.now().toString());
        ctx.put("employees_on_leave",          onLeave);
        ctx.put("employees_on_leave_count",    onLeave.size());
        ctx.put("total_employees_checked",     allNames.size());
        ctx.put("employees_not_on_leave_count", allNames.size() - onLeave.size());
        try { return mapper.writeValueAsString(ctx); } catch (Exception e) { return "{}"; }
    }
}
