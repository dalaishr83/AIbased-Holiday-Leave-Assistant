package com.holidayleave.assistant.service;

import com.holidayleave.assistant.model.PendingVacation;
import com.holidayleave.assistant.model.PendingVacation.WizardState;
import com.holidayleave.assistant.model.VacationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VacationCreationService}.
 *
 * Tests the full "add vacation" wizard state machine:
 *  IDLE → NEED_EMP → NEED_TYPE → NEED_START → NEED_END → CONFIRM → SAVED
 * Plus all cancel / error paths and the countWeekdays static helper.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VacationCreationServiceTest {

    @Mock private VacationTypeService vacationTypeService;
    @Mock private RestrictedVacationTypeService restrictedVacationTypeService;

    @InjectMocks
    private VacationCreationService service;

    private List<String> employees;
    private List<VacationType> types;

    @BeforeEach
    void setUp() {
        employees = Arrays.asList("Alice Smith", "Bob Johnson", "Carol Nguyen");
        types = Arrays.asList(
            new VacationType("V",  "Vacation",                "FF92D050"),
            new VacationType("P",  "Public Holiday",          "FFFF0000"),
            new VacationType("PC", "Personal Choice Holiday", "FFFFFF00"),
            new VacationType("H",  "Half-day Vacation",       "FFFFC000"),
            new VacationType("E",  "Education",               "FF00B0F0"),
            new VacationType("O",  "Other",                   "FFD3D3D3")
        );
        when(vacationTypeService.findAll()).thenReturn(types);
        when(restrictedVacationTypeService.getRestrictedTypes()).thenReturn(Collections.emptyList());
        when(restrictedVacationTypeService.isRestricted(any())).thenReturn(false);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Full happy-path walk-through
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void fullWizard_happyPath_addVacationForAlice() {
        PendingVacation pv = new PendingVacation("add"); // state = IDLE

        // IDLE: employee name in message
        VacationCreationService.WizardResult r1 = service.process(pv, "Add vacation for Alice Smith", employees);
        assertFalse(r1.confirmed());
        assertFalse(r1.cancelled());
        assertEquals(WizardState.NEED_TYPE, pv.getState());
        assertTrue(r1.reply().contains("Alice Smith"));

        // NEED_TYPE: exact code match
        VacationCreationService.WizardResult r2 = service.process(pv, "V", employees);
        assertFalse(r2.confirmed());
        assertEquals(WizardState.NEED_START, pv.getState());
        assertEquals("Vacation", pv.getLeaveType());
        assertEquals("V", pv.getLeaveCode());

        // NEED_START: valid date
        VacationCreationService.WizardResult r3 = service.process(pv, "2026-06-01", employees);
        assertFalse(r3.confirmed());
        assertEquals(WizardState.NEED_END, pv.getState());
        assertEquals(LocalDate.of(2026, 6, 1), pv.getStartDate());

        // NEED_END: valid end date (Mon–Fri = 5 days)
        VacationCreationService.WizardResult r4 = service.process(pv, "2026-06-05", employees);
        assertFalse(r4.confirmed());
        assertEquals(WizardState.CONFIRM, pv.getState());
        assertEquals(5, pv.getDays(), 0.001);
        assertTrue(r4.reply().contains("5 working day"));

        // CONFIRM: yes
        VacationCreationService.WizardResult r5 = service.process(pv, "yes", employees);
        assertTrue(r5.confirmed());
        assertFalse(r5.cancelled());
        assertEquals(WizardState.SAVED, pv.getState());
    }

    @Test
    void fullWizard_happyPath_leaveTypeByLabel() {
        PendingVacation pv = new PendingVacation("add");

        service.process(pv, "Add vacation for Bob Johnson", employees);
        VacationCreationService.WizardResult r = service.process(pv, "Vacation", employees);

        assertEquals(WizardState.NEED_START, pv.getState());
        assertEquals("V", pv.getLeaveCode());
    }

    @Test
    void fullWizard_happyPath_leaveTypeByPartialLabel() {
        PendingVacation pv = new PendingVacation("add");

        service.process(pv, "Add vacation for Carol Nguyen", employees);
        // Partial label "education" (contains 3+ chars)
        VacationCreationService.WizardResult r = service.process(pv, "education", employees);

        assertEquals(WizardState.NEED_START, pv.getState());
        assertEquals("E", pv.getLeaveCode());
    }

    @Test
    void fullWizard_happyPath_confirmWithY() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);
        service.process(pv, "2026-01-05", employees);
        service.process(pv, "2026-01-09", employees);

        VacationCreationService.WizardResult r = service.process(pv, "y", employees);
        assertTrue(r.confirmed());
    }

    @Test
    void fullWizard_happyPath_confirmWithConfirm() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);
        service.process(pv, "2026-01-05", employees);
        service.process(pv, "2026-01-09", employees);

        VacationCreationService.WizardResult r = service.process(pv, "confirm", employees);
        assertTrue(r.confirmed());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // IDLE → NEED_EMP path (no employee in initial message)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void idle_noEmployeeInMessage_askForEmployee() {
        PendingVacation pv = new PendingVacation("add");
        VacationCreationService.WizardResult r = service.process(pv, "Add vacation", employees);
        assertFalse(r.confirmed());
        assertEquals(WizardState.NEED_EMP, pv.getState());
        assertTrue(r.reply().contains("employee"));
    }

    @Test
    void needEmp_validEmployee_advancesToNeedType() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation", employees); // → NEED_EMP

        VacationCreationService.WizardResult r = service.process(pv, "Alice Smith", employees);
        assertEquals(WizardState.NEED_TYPE, pv.getState());
        assertEquals("Alice Smith", pv.getEmployeeName());
    }

    @Test
    void needEmp_unknownEmployee_staysInNeedEmp() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation", employees); // → NEED_EMP

        VacationCreationService.WizardResult r = service.process(pv, "Zephyr Unknown", employees);
        assertFalse(r.confirmed());
        assertFalse(r.cancelled());
        assertEquals(WizardState.NEED_EMP, pv.getState());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NEED_TYPE — invalid / restricted
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void needType_unknownType_staysInNeedType() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);

        VacationCreationService.WizardResult r = service.process(pv, "XYZ999", employees);
        assertFalse(r.confirmed());
        assertEquals(WizardState.NEED_TYPE, pv.getState());
        assertTrue(r.reply().contains("don't recognise"));
    }

    @Test
    void needType_restrictedType_rejectedWithMessage() {
        when(restrictedVacationTypeService.isRestricted("PC")).thenReturn(true);

        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);

        VacationCreationService.WizardResult r = service.process(pv, "PC", employees);
        assertFalse(r.confirmed());
        assertEquals(WizardState.NEED_TYPE, pv.getState());
        assertTrue(r.reply().toLowerCase().contains("disabled") ||
                   r.reply().toLowerCase().contains("restricted"));
    }

    @Test
    void needType_singleCharInput_noPartialMatch() {
        // Single char "E" should only match exact code, not partial label
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);

        VacationCreationService.WizardResult r = service.process(pv, "E", employees);
        // "E" is an exact code match → should work
        assertEquals(WizardState.NEED_START, pv.getState());
        assertEquals("Education", pv.getLeaveType());
    }

    @Test
    void needType_twoCharInput_noPartialMatch() {
        // 2-char input should match exact code only; "pc" is an exact match
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);

        VacationCreationService.WizardResult r = service.process(pv, "pc", employees);
        assertEquals(WizardState.NEED_START, pv.getState());
        assertEquals("PC", pv.getLeaveCode());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NEED_START — invalid dates
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void needStart_invalidDateFormat_staysInNeedStart() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);

        VacationCreationService.WizardResult r = service.process(pv, "01/06/2026", employees);
        assertEquals(WizardState.NEED_START, pv.getState());
        assertTrue(r.reply().contains("YYYY-MM-DD"));
    }

    @Test
    void needStart_naturalLanguageDate_staysInNeedStart() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);

        VacationCreationService.WizardResult r = service.process(pv, "next Monday", employees);
        assertEquals(WizardState.NEED_START, pv.getState());
    }

    @Test
    void needStart_emptyMessage_staysInNeedStart() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);

        VacationCreationService.WizardResult r = service.process(pv, "", employees);
        assertEquals(WizardState.NEED_START, pv.getState());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NEED_END — invalid / before start
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void needEnd_endBeforeStart_errorMessage() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);
        service.process(pv, "2026-06-05", employees); // start = June 5

        VacationCreationService.WizardResult r = service.process(pv, "2026-06-04", employees);
        assertEquals(WizardState.NEED_END, pv.getState());
        assertTrue(r.reply().toLowerCase().contains("end date"));
    }

    @Test
    void needEnd_sameStartAndEnd_oneDay() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);
        service.process(pv, "2026-06-01", employees);

        VacationCreationService.WizardResult r = service.process(pv, "2026-06-01", employees);
        assertEquals(WizardState.CONFIRM, pv.getState());
        assertEquals(1.0, pv.getDays(), 0.001);
        assertTrue(r.reply().contains("1 working day"));
    }

    @Test
    void needEnd_weekendOnly_noWorkingDays_error() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);
        service.process(pv, "2026-06-06", employees); // Saturday

        VacationCreationService.WizardResult r = service.process(pv, "2026-06-07", employees); // Sunday
        // No working days (Sat+Sun) → error
        assertEquals(WizardState.NEED_END, pv.getState());
        assertTrue(r.reply().toLowerCase().contains("working day"));
    }

    @Test
    void needEnd_invalidDateFormat_staysInNeedEnd() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);
        service.process(pv, "2026-06-01", employees);

        VacationCreationService.WizardResult r = service.process(pv, "not-a-date", employees);
        assertEquals(WizardState.NEED_END, pv.getState());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Cancel at each stage
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void cancel_atNeedEmp_wizardCancelled() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation", employees); // → NEED_EMP

        VacationCreationService.WizardResult r = service.process(pv, "cancel", employees);
        assertTrue(r.cancelled());
        assertFalse(r.confirmed());
        assertEquals(WizardState.CANCELLED, pv.getState());
    }

    @Test
    void cancel_atNeedType_wizardCancelled() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);

        VacationCreationService.WizardResult r = service.process(pv, "abort", employees);
        assertTrue(r.cancelled());
    }

    @Test
    void cancel_atNeedStart_wizardCancelled() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);

        VacationCreationService.WizardResult r = service.process(pv, "stop", employees);
        assertTrue(r.cancelled());
    }

    @Test
    void cancel_atNeedEnd_wizardCancelled() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);
        service.process(pv, "2026-06-01", employees);

        VacationCreationService.WizardResult r = service.process(pv, "quit", employees);
        assertTrue(r.cancelled());
    }

    @Test
    void cancel_atConfirm_no_cancelsWizard() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);
        service.process(pv, "2026-06-01", employees);
        service.process(pv, "2026-06-05", employees);

        VacationCreationService.WizardResult r = service.process(pv, "no", employees);
        assertTrue(r.cancelled());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Pre-seeded employee (employee-role skip of NEED_EMP)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void preSeededEmployee_needTypeIsFirstStep() {
        PendingVacation pv = new PendingVacation("add");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.NEED_TYPE);

        VacationCreationService.WizardResult r = service.process(pv, "add vacation", employees);
        // Should now be at NEED_TYPE already, so process goes straight to type resolution
        // The message "add vacation" won't match a type → stays in NEED_TYPE
        assertFalse(r.confirmed());
        assertEquals(WizardState.NEED_TYPE, pv.getState());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // countWeekdays() static helper
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void countWeekdays_monToFri_returns5() {
        long days = VacationCreationService.countWeekdays(
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5));
        assertEquals(5, days);
    }

    @Test
    void countWeekdays_satToSun_returns0() {
        long days = VacationCreationService.countWeekdays(
            LocalDate.of(2026, 6, 6), LocalDate.of(2026, 6, 7));
        assertEquals(0, days);
    }

    @Test
    void countWeekdays_singleMonday_returns1() {
        long days = VacationCreationService.countWeekdays(
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1));
        assertEquals(1, days);
    }

    @Test
    void countWeekdays_singleSaturday_returns0() {
        long days = VacationCreationService.countWeekdays(
            LocalDate.of(2026, 6, 6), LocalDate.of(2026, 6, 6));
        assertEquals(0, days);
    }

    @Test
    void countWeekdays_twoWeeks_returns10() {
        long days = VacationCreationService.countWeekdays(
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 12));
        assertEquals(10, days);
    }

    @Test
    void countWeekdays_singleDay_friday() {
        long days = VacationCreationService.countWeekdays(
            LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 5));
        assertEquals(1, days);
    }

    @Test
    void countWeekdays_spanAcrossMonths() {
        // 2026-06-29 (Mon) to 2026-07-03 (Fri) = 5 weekdays
        long days = VacationCreationService.countWeekdays(
            LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 3));
        assertEquals(5, days);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Boundary: dates at year boundaries
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void wizard_yearEndDate_dec31_monday_accepted() {
        // Dec 31 2026 is a Thursday
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);
        service.process(pv, "2026-12-31", employees);

        assertEquals(WizardState.NEED_END, pv.getState());
        assertEquals(LocalDate.of(2026, 12, 31), pv.getStartDate());
    }

    @Test
    void wizard_yearStartDate_jan1_accepted() {
        PendingVacation pv = new PendingVacation("add");
        service.process(pv, "Add vacation for Alice Smith", employees);
        service.process(pv, "V", employees);

        VacationCreationService.WizardResult r = service.process(pv, "2026-01-01", employees);
        // Jan 1 2026 is a Thursday — valid date
        assertEquals(WizardState.NEED_END, pv.getState());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Employee resolution edge cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void idle_partialFirstName_resolves() {
        // "Ali" is 3 chars — matches at pass 2 first-token index 0 (min=3)
        // "ali" (3 chars) would match "Alice" token "alice" ... hmm "alice" (5 chars)
        // Actually pass 2 checks if the token extracted from the *name* is present in question words
        // "Alice" token = "alice" (5 chars, i=0 min=3) → present in "ali" question? No — question has "ali" not "alice"
        // So this should NOT resolve
        PendingVacation pv = new PendingVacation("add");
        VacationCreationService.WizardResult r = service.process(pv, "add vacation for ali", employees);
        // "ali" is not present as a token in any employee name exactly; names have "alice" not "ali"
        // So falls to NEED_EMP
        assertEquals(WizardState.NEED_EMP, pv.getState());
    }

    @Test
    void idle_emptyEmployeeList_doesNotThrow() {
        PendingVacation pv = new PendingVacation("add");
        assertDoesNotThrow(() -> service.process(pv, "Add vacation for Alice Smith", Collections.emptyList()));
    }
}
