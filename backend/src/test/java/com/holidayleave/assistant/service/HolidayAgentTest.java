package com.holidayleave.assistant.service;

import com.holidayleave.assistant.analysis.LeaveAnalysisService;
import com.holidayleave.assistant.llm.LLMService;
import com.holidayleave.assistant.model.LeaveRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HolidayAgent}.
 *
 * Covers: intent detection, employee name resolution (all 3 passes),
 * year/month extraction, context-shape selection, pronoun history resolution,
 * all-employees aggregation, generic context fallback, ask() LLM delegation,
 * plus boundary and negative scenarios.
 *
 * The LLMService and AppState are mocked â€” no real HTTP calls are made.
 */
@ExtendWith(MockitoExtension.class)
class HolidayAgentTest {

    @Mock private LLMService llmService;
    @Mock private LeaveAnalysisService analysisService;
    @Mock private AppState appState;

    @InjectMocks
    private HolidayAgent agent;

    private List<LeaveRecord> records;

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private LeaveRecord rec(String name, String start, String end, double days, String type) {
        return new LeaveRecord(name, LocalDate.parse(start), LocalDate.parse(end), days, type, null);
    }

    private List<Map<String, String>> historyWith(String role, String content) {
        Map<String, String> msg = new HashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        return Collections.singletonList(msg);
    }

    @BeforeEach
    void setUp() {
        records = new ArrayList<>(Arrays.asList(
            rec("Alice Smith",   "2026-01-05", "2026-01-09", 5, "V"),
            rec("Bob Johnson",   "2026-02-02", "2026-02-06", 5, "V"),
            rec("Carol Nguyen",  "2026-03-02", "2026-03-06", 5, "V")
        ));
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Intent detection â€” isAddVacationIntent
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void isAddVacationIntent_addVacationKeyword_true() {
        assertTrue(agent.isAddVacationIntent("Please add vacation for Alice"));
    }

    @Test
    void isAddVacationIntent_bookLeaveKeyword_true() {
        assertTrue(agent.isAddVacationIntent("Book leave from Jan 5 to Jan 9"));
    }

    @Test
    void isAddVacationIntent_requestHoliday_true() {
        assertTrue(agent.isAddVacationIntent("I want to request holiday next week"));
    }

    @Test
    void isAddVacationIntent_scheduleVacation_true() {
        assertTrue(agent.isAddVacationIntent("schedule vacation for next month"));
    }

    @Test
    void isAddVacationIntent_noKeyword_false() {
        assertFalse(agent.isAddVacationIntent("How many days has Alice taken?"));
    }

    @Test
    void isAddVacationIntent_deleteKeyword_false() {
        assertFalse(agent.isAddVacationIntent("delete vacation for Bob"));
    }

    @Test
    void isAddVacationIntent_emptyMessage_false() {
        assertFalse(agent.isAddVacationIntent(""));
    }

    @Test
    void isAddVacationIntent_caseInsensitive() {
        assertTrue(agent.isAddVacationIntent("ADD VACATION for Carol"));
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Intent detection â€” isDeleteVacationIntent
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void isDeleteVacationIntent_deleteVacation_true() {
        assertTrue(agent.isDeleteVacationIntent("delete vacation for Bob"));
    }

    @Test
    void isDeleteVacationIntent_removeLeave_true() {
        assertTrue(agent.isDeleteVacationIntent("remove leave for this week"));
    }

    @Test
    void isDeleteVacationIntent_cancelLeave_true() {
        assertTrue(agent.isDeleteVacationIntent("cancel leave on Monday"));
    }

    @Test
    void isDeleteVacationIntent_undoVacation_true() {
        assertTrue(agent.isDeleteVacationIntent("undo vacation I just added"));
    }

    @Test
    void isDeleteVacationIntent_eraseLeave_true() {
        assertTrue(agent.isDeleteVacationIntent("erase leave for Carol"));
    }

    @Test
    void isDeleteVacationIntent_noKeyword_false() {
        assertFalse(agent.isDeleteVacationIntent("How many days is Alice taking?"));
    }

    @Test
    void isDeleteVacationIntent_emptyMessage_false() {
        assertFalse(agent.isDeleteVacationIntent(""));
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Intent detection â€” isReportIntent
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void isReportIntent_generateReport_true() {
        assertTrue(agent.isReportIntent("generate report for Alice"));
    }

    @Test
    void isReportIntent_createReport_true() {
        assertTrue(agent.isReportIntent("create report"));
    }

    @Test
    void isReportIntent_yearlyReport_true() {
        assertTrue(agent.isReportIntent("I need the yearly report for Bob"));
    }

    @Test
    void isReportIntent_htmlReport_true() {
        assertTrue(agent.isReportIntent("html report please"));
    }

    @Test
    void isReportIntent_noKeyword_false() {
        assertFalse(agent.isReportIntent("How many leave days does Carol have?"));
    }

    @Test
    void isReportIntent_emptyMessage_false() {
        assertFalse(agent.isReportIntent(""));
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Intent detection â€” isAllEmployeesQuery
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void isAllEmployeesQuery_allEmployee_true() {
        assertTrue(agent.isAllEmployeesQuery("show all employee leave"));
    }

    @Test
    void isAllEmployeesQuery_everyone_true() {
        assertTrue(agent.isAllEmployeesQuery("how many days has everyone taken?"));
    }

    @Test
    void isAllEmployeesQuery_allStaff_true() {
        assertTrue(agent.isAllEmployeesQuery("all staff report please"));
    }

    @Test
    void isAllEmployeesQuery_allWorkers_true() {
        assertTrue(agent.isAllEmployeesQuery("show all workers vacation summary"));
    }

    @Test
    void isAllEmployeesQuery_specificEmployee_false() {
        assertFalse(agent.isAllEmployeesQuery("show Alice's leave"));
    }

    @Test
    void isAllEmployeesQuery_emptyMessage_false() {
        assertFalse(agent.isAllEmployeesQuery(""));
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Employee name resolution â€” resolveEmployeeName
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /** Pass 1: full name substring match. */
    @Test
    void resolveEmployeeName_pass1_exactFullNameMatch() {
        String result = agent.resolveEmployeeName("What leave has Alice Smith taken?", records);
        assertEquals("Alice Smith", result);
    }

    /** Pass 1: lowercase question still matches. */
    @Test
    void resolveEmployeeName_pass1_caseInsensitiveMatch() {
        String result = agent.resolveEmployeeName("what leave has alice smith taken?", records);
        assertEquals("Alice Smith", result);
    }

    /** Pass 2: first token with â‰¥ 3 chars matches first name. */
    @Test
    void resolveEmployeeName_pass2_firstNameToken() {
        String result = agent.resolveEmployeeName("How many days has Bob taken?", records);
        assertEquals("Bob Johnson", result);
    }

    /** Pass 2: second token with â‰¥ 4 chars matches last name. */
    @Test
    void resolveEmployeeName_pass2_lastNameToken() {
        String result = agent.resolveEmployeeName("Nguyen has how many vacation days?", records);
        assertEquals("Carol Nguyen", result);
    }

    /** Pass 3: multi-token scoring returns best match. */
    @Test
    void resolveEmployeeName_pass3_bestTokenScore() {
        String result = agent.resolveEmployeeName("Johnson took days off", records);
        assertEquals("Bob Johnson", result);
    }

    @Test
    void resolveEmployeeName_noMatch_returnsNull() {
        String result = agent.resolveEmployeeName("Totally unrelated question", records);
        assertNull(result);
    }

    @Test
    void resolveEmployeeName_emptyRecords_returnsNull() {
        String result = agent.resolveEmployeeName("How much leave has Alice taken?", Collections.emptyList());
        assertNull(result);
    }

    @Test
    void resolveEmployeeName_shortTokenBelowMinLength_noMatch() {
        // "Al" is 2 chars â€” below the 3-char min for first token in pass 2
        String result = agent.resolveEmployeeName("Al took some days", records);
        // Pass 1 will not match (no "Alice Smith" as substring), pass 2 needs â‰¥3 chars
        // Pass 3 requires â‰¥4 chars â€” so null
        assertNull(result);
    }

    @Test
    void resolveEmployeeName_ambiguousShortNameNotMatchedByShortToken() {
        // Token "Bob" (3 chars) matches at pass 2 index 0 (min=3)
        String result = agent.resolveEmployeeName("Bob took leave", records);
        assertEquals("Bob Johnson", result);
    }

    @Test
    void resolveEmployeeName_multipleNamesInQuestion_firstMatchReturned() {
        // "alice smith bob johnson" â€” pass 1 will match Alice Smith first
        String result = agent.resolveEmployeeName("alice smith bob johnson leave", records);
        assertEquals("Alice Smith", result);
    }

    // Natural-language phrasing equivalence â€” regression protection
    @Test
    void resolveEmployeeName_naturalLanguageVariants_allResolve() {
        String[] variants = {
            "How many days has Alice Smith taken?",
            "Show me Alice Smith's vacation",
            "alice smith vacation in 2026",
            "What is Alice Smith's remaining leave?",
        };
        for (String q : variants) {
            String result = agent.resolveEmployeeName(q, records);
            assertEquals("Alice Smith", result,
                "Expected Alice Smith from: " + q);
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Employee name resolution from history â€” resolveEmployeeNameFromHistory
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void resolveEmployeeNameFromHistory_findsNameInHistory() {
        List<Map<String, String>> history = historyWith("user", "Alice Smith took 5 days off");
        String result = agent.resolveEmployeeNameFromHistory(history, records);
        assertEquals("Alice Smith", result);
    }

    @Test
    void resolveEmployeeNameFromHistory_latestMatchReturned() {
        // History has Alice at index 0, Bob at index 1 â€” reverse scan returns Bob first
        List<Map<String, String>> history = new ArrayList<>();
        Map<String, String> m1 = new HashMap<>(); m1.put("role","user"); m1.put("content","Alice Smith left");
        Map<String, String> m2 = new HashMap<>(); m2.put("role","assistant"); m2.put("content","Bob Johnson is on leave");
        history.add(m1);
        history.add(m2);
        String result = agent.resolveEmployeeNameFromHistory(history, records);
        // Reverse scan: Bob is found at index 1 first
        assertEquals("Bob Johnson", result);
    }

    @Test
    void resolveEmployeeNameFromHistory_nullHistory_returnsNull() {
        assertNull(agent.resolveEmployeeNameFromHistory(null, records));
    }

    @Test
    void resolveEmployeeNameFromHistory_emptyHistory_returnsNull() {
        assertNull(agent.resolveEmployeeNameFromHistory(Collections.emptyList(), records));
    }

    @Test
    void resolveEmployeeNameFromHistory_historyWithNoEmployeeNames_returnsNull() {
        List<Map<String, String>> history = historyWith("user", "What is the weather today?");
        assertNull(agent.resolveEmployeeNameFromHistory(history, records));
    }

    @Test
    void resolveEmployeeNameFromHistory_nullContentInHistory_skipped() {
        Map<String, String> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", null); // null content
        List<Map<String, String>> history = Collections.singletonList(msg);
        // Should not throw, should return null
        assertNull(agent.resolveEmployeeNameFromHistory(history, records));
    }

    @Test
    void resolveEmployeeNameFromHistory_pronounInQuestion_resolvedFromHistory() {
        // Simulates "she took 3 days" follow-up â€” history has the name
        List<Map<String, String>> history = historyWith("user", "Alice Smith is on vacation");
        // This is the history-based pronoun resolution
        String result = agent.resolveEmployeeNameFromHistory(history, records);
        assertEquals("Alice Smith", result);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Year extraction
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void extractYear_explicitYear_returned() throws Exception {
        int year = invokeExtractYear("How many days in 2025?", records);
        assertEquals(2025, year);
    }

    @Test
    void extractYear_noYearInQuestion_fallsBackToMaxRecordYear() throws Exception {
        List<LeaveRecord> r = Collections.singletonList(rec("Alice", "2026-01-05", "2026-01-09", 5, "V"));
        int year = invokeExtractYear("How many days has Alice taken?", r);
        assertEquals(2026, year);
    }

    @Test
    void extractYear_noYearNoRecords_fallsBackToCurrentYear() throws Exception {
        int current = LocalDate.now().getYear();
        int year = invokeExtractYear("How many days has Alice taken?", Collections.emptyList());
        // Should be either current year or higher â€” at minimum current year
        assertTrue(year >= current);
    }

    @Test
    void extractYear_multipleYearsInQuestion_takesFirst() throws Exception {
        int year = invokeExtractYear("Compare 2025 vs 2026 leave", records);
        assertEquals(2025, year);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Month extraction
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void extractMonth_januaryFull_returns1() throws Exception {
        assertEquals(1, invokeExtractMonth("vacation in January"));
    }

    @Test
    void extractMonth_julyAbbrev_returns7() throws Exception {
        assertEquals(7, invokeExtractMonth("How many days in Jul?"));
    }

    @Test
    void extractMonth_decemberFull_returns12() throws Exception {
        assertEquals(12, invokeExtractMonth("Leave in December 2026"));
    }

    @Test
    void extractMonth_noMonth_returnsNull() throws Exception {
        assertNull(invokeExtractMonth("How many days in 2026?"));
    }

    @Test
    void extractMonth_mayFull_returns5() throws Exception {
        assertEquals(5, invokeExtractMonth("Vacation in May"));
    }

    @Test
    void extractMonth_caseInsensitive_august() throws Exception {
        assertEquals(8, invokeExtractMonth("VACATION IN AUGUST"));
    }

    @Test
    void extractMonth_allAbbreviations() throws Exception {
        String[] abbrevs = {"jan","feb","mar","apr","jun","jul","aug","sep","oct","nov","dec"};
        int[]    months  = {  1,    2,    3,    4,    6,    7,    8,    9,   10,   11,   12};
        for (int i = 0; i < abbrevs.length; i++) {
            assertEquals(months[i], invokeExtractMonth("days in " + abbrevs[i]),
                "Abbreviation " + abbrevs[i]);
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // ask() â€” LLM delegation and context selection
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void ask_delegatesToLlm_returnsReply() {
        when(appState.getConversationHistory()).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("Alice has 5 days left.");
        when(analysisService.analyse(anyList(), eq("Alice Smith"), anyInt()))
                .thenReturn(stubAnalysisResult("Alice Smith", 2026));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        String reply = agent.ask("How many days does Alice Smith have left?", records);

        assertEquals("Alice has 5 days left.", reply);
        verify(llmService).ask(isNull(), anyString(),
                eq("How many days does Alice Smith have left?"), anyList());
    }

    @Test
    void ask_addsToHistory() {
        when(appState.getConversationHistory()).thenReturn(Collections.emptyList());
        when(llmService.ask(any(), any(), any(), any())).thenReturn("OK");
        when(analysisService.analyse(anyList(), anyString(), anyInt()))
                .thenReturn(stubAnalysisResult("Alice Smith", 2026));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        agent.ask("How many days does Alice Smith have left?", records);

        verify(appState).addToHistory(eq("How many days does Alice Smith have left?"), eq("OK"));
    }

    @Test
    void ask_allEmployeesQuery_usesShapeB() {
        when(appState.getConversationHistory()).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("Summary of all employees.");

        String reply = agent.ask("Show all employee leave for 2026", records);

        assertEquals("Summary of all employees.", reply);
        // Verify that the context passed to LLM contains "all_employees_summary"
        verify(llmService).ask(isNull(), argThat(ctx -> ctx.contains("all_employees_summary")),
                anyString(), anyList());
    }

    @Test
    void ask_unknownEmployee_usesShapeC() {
        when(appState.getConversationHistory()).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("I don't know who you mean.");

        String reply = agent.ask("How many days off does Zephyr Moonbeam have?", records);

        assertNotNull(reply);
        // Context should contain "employees" (Shape C) not employee-specific fields
        verify(llmService).ask(isNull(), argThat(ctx -> ctx.contains("\"employees\"")),
                anyString(), anyList());
    }

    @Test
    void ask_pronounWithHistory_resolvesFromHistory() {
        // History contains Alice Smith; question uses "she" â†’ should resolve to Alice
        List<Map<String, String>> history = historyWith("user", "Alice Smith took leave");
        when(appState.getConversationHistory()).thenReturn(history);
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("She has 3 days left.");
        when(analysisService.analyse(anyList(), eq("Alice Smith"), anyInt()))
                .thenReturn(stubAnalysisResult("Alice Smith", 2026));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        String reply = agent.ask("How many days does she have left?", records);
        assertEquals("She has 3 days left.", reply);

        // Context should be Shape A (employee-specific) not Shape C
        verify(llmService).ask(isNull(), argThat(ctx -> ctx.contains("employee_name")),
                anyString(), anyList());
    }

    @Test
    void ask_emptyRecords_doesNotThrow() {
        when(appState.getConversationHistory()).thenReturn(Collections.emptyList());
        when(llmService.ask(any(), any(), any(), any())).thenReturn("No data available.");

        assertDoesNotThrow(() -> agent.ask("How many days?", Collections.emptyList()));
    }

    @Test
    void ask_specificMonth_contextContainsDaysInRequestedMonth() {
        when(appState.getConversationHistory()).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("Alice took 3 days in March.");
        when(analysisService.analyse(anyList(), eq("Alice Smith"), anyInt()))
                .thenReturn(stubAnalysisResult("Alice Smith", 2026));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        agent.ask("How many days did Alice Smith take in March?", records);

        verify(llmService).ask(isNull(),
                argThat(ctx -> ctx.contains("total_all_leave_types_in_month")),
                anyString(), anyList());
    }

    @Test
    void ask_yearExplicitInQuestion_correctYearUsed() {
        // Records are 2026; question asks for 2025
        when(appState.getConversationHistory()).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("In 2025 Alice had 0 days.");
        when(analysisService.analyse(anyList(), eq("Alice Smith"), eq(2025)))
                .thenReturn(stubAnalysisResult("Alice Smith", 2025));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        agent.ask("How many days did Alice Smith take in 2025?", records);

        verify(analysisService).analyse(anyList(), eq("Alice Smith"), eq(2025));
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Regression â€” natural-language query equivalence
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /**
     * Equivalent NL queries for "how many days did Alice take in July" must all
     * resolve to the same employee and the same month.
     */
    @Test
    void regression_equivalentNLQueriesForAliceJuly_sameResolution() throws Exception {
        String[] queries = {
            "How many vacation days did Alice Smith take in July?",
            "Alice Smith's leave in july 2026",
            "Show leave for Alice Smith in July 2026",
            "alice smith july leave",
        };
        for (String q : queries) {
            String emp = agent.resolveEmployeeName(q, records);
            Integer month = invokeExtractMonth(q);
            assertEquals("Alice Smith", emp, "Employee resolution failed for: " + q);
            assertEquals(7, month, "Month resolution failed for: " + q);
        }
    }

    @Test
    void regression_allEmployeesKeywords_allTriggerShapeB() {
        String[] queries = {"all employee leave", "all staff vacation", "everyone took leave", "all workers"};
        for (String q : queries) {
            assertTrue(agent.isAllEmployeesQuery(q),
                "isAllEmployeesQuery returned false for: " + q);
        }
    }

    @Test
    void regression_deleteKeywords_allTriggerDeleteIntent() {
        String[] queries = {
            "delete vacation for Bob",
            "remove leave for Alice",
            "cancel leave on Thursday",
            "undo vacation last week",
            "erase leave entry"
        };
        for (String q : queries) {
            assertTrue(agent.isDeleteVacationIntent(q),
                "isDeleteVacationIntent returned false for: " + q);
        }
    }

    @Test
    void regression_addKeywords_allTriggerAddIntent() {
        String[] queries = {
            "add vacation for Bob",
            "book leave next week",
            "request vacation for Carol",
            "new leave for Alice",
            "create vacation entry",
            "record leave for Bob",
            "log vacation for Carol",
            "schedule vacation for Alice"
        };
        for (String q : queries) {
            assertTrue(agent.isAddVacationIntent(q),
                "isAddVacationIntent returned false for: " + q);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // extractMonths() — range detection
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void extractMonths_singleMonth_returnsOneElement() throws Exception {
        List<Integer> result = invokeExtractMonths("leave in March");
        assertEquals(1, result.size());
        assertEquals(3, result.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractMonths_twoDistinctMonths_returnsBoth() throws Exception {
        List<Integer> result = invokeExtractMonths("from March to April");
        assertEquals(2, result.size());
        assertEquals(3, result.get(0));
        assertEquals(4, result.get(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractMonths_sameMonthTwice_returnsOne() throws Exception {
        List<Integer> result = invokeExtractMonths("leave in March and also March");
        assertEquals(1, result.size());
        assertEquals(3, result.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractMonths_noMonth_returnsEmptyList() throws Exception {
        List<Integer> result = invokeExtractMonths("how many days in 2026?");
        assertTrue(result.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractMonths_abbreviations_detected() throws Exception {
        List<Integer> result = invokeExtractMonths("from Mar to Apr");
        assertEquals(2, result.size());
        assertEquals(3, result.get(0));
        assertEquals(4, result.get(1));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ask() — range query emits is_range_query, total_all_leave_types_in_range,
    //          by_month_by_type, range_start_month_name, range_end_month_name
    //          and does NOT emit context_scope_month_name (single-month field)
    //          and does NOT emit days_in_requested_range (old name — removed)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void ask_rangeQuery_contextContainsRangeFields() {
        when(appState.getConversationHistory()).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("range answer");
        when(analysisService.analyse(anyList(), eq("Alice Smith"), anyInt()))
                .thenReturn(stubAnalysisResult("Alice Smith", 2026));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        agent.ask("How many days did Alice Smith take from March to April?", records);

        verify(llmService).ask(isNull(),
                argThat(ctx -> ctx.contains("is_range_query")
                             && ctx.contains("total_all_leave_types_in_range")
                             && ctx.contains("by_month_by_type")
                             && ctx.contains("range_start_month_name")
                             && ctx.contains("range_end_month_name")
                             && !ctx.contains("context_scope_month_name")
                             && !ctx.contains("days_in_requested_range")),
                anyString(), anyList());
    }

    @Test
    void ask_singleMonthQuery_noRangeFields() {
        when(appState.getConversationHistory()).thenReturn(Collections.emptyList());
        when(llmService.ask(isNull(), anyString(), anyString(), anyList()))
                .thenReturn("single month answer");
        when(analysisService.analyse(anyList(), eq("Alice Smith"), anyInt()))
                .thenReturn(stubAnalysisResult("Alice Smith", 2026));
        when(analysisService.consumedToDate(anyList())).thenReturn(0.0);

        agent.ask("How many days did Alice Smith take in March?", records);

        verify(llmService).ask(isNull(),
                argThat(ctx -> ctx.contains("context_scope_month_name")
                             && !ctx.contains("is_range_query")),
                anyString(), anyList());
    }



    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Helpers to invoke private methods via reflection
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private int invokeExtractYear(String question, List<LeaveRecord> recs) throws Exception {
        Method m = HolidayAgent.class.getDeclaredMethod("extractYear", String.class, List.class);
        m.setAccessible(true);
        return (int) m.invoke(agent, question, recs);
    }

    private Integer invokeExtractMonth(String question) throws Exception {
        Method m = HolidayAgent.class.getDeclaredMethod("extractMonth", String.class);
        m.setAccessible(true);
        return (Integer) m.invoke(agent, question);
    }

    @SuppressWarnings("unchecked")
    private List<Integer> invokeExtractMonths(String question) throws Exception {
        Method m = HolidayAgent.class.getDeclaredMethod("extractMonths", String.class);
        m.setAccessible(true);
        return (List<Integer>) m.invoke(agent, question);
    }



    // â”€â”€ Stub helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private com.holidayleave.assistant.model.LeaveAnalysisResult stubAnalysisResult(
            String name, int year) {
        return new com.holidayleave.assistant.model.LeaveAnalysisResult(
            name, year, 25.0, 5.0, 20.0, 20.0,
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            5, 2.08, new ArrayList<>()
        );
    }
}
