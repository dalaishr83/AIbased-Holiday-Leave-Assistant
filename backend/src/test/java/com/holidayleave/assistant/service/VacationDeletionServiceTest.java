package com.holidayleave.assistant.service;

import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.model.PendingVacation;
import com.holidayleave.assistant.model.PendingVacation.WizardState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VacationDeletionService}.
 *
 * Tests the full delete-vacation wizard state machine:
 *   DELETE_IDLE → DELETE_NEED_EMP → DELETE_NEED_START → DELETE_NEED_END → DELETE_CONFIRM → DELETED
 * Plus coverage-validation and cancellation at each step.
 */
@ExtendWith(MockitoExtension.class)
class VacationDeletionServiceTest {

    @InjectMocks
    private VacationDeletionService service;

    private List<String> employees;
    private List<LeaveRecord> allRecords;

    @BeforeEach
    void setUp() {
        employees = Arrays.asList("Alice Smith", "Bob Johnson", "Carol Nguyen");

        // Alice has V from 2026-06-01 to 2026-06-05 (Mon-Fri)
        // Bob has V from 2026-07-06 to 2026-07-10 (Mon-Fri)
        allRecords = Arrays.asList(
            new LeaveRecord("Alice Smith", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5), 5, "V", null),
            new LeaveRecord("Bob Johnson", LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 10), 5, "V", null)
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Full happy-path walk-through
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void fullWizard_happyPath_deleteAliceVacation() {
        PendingVacation pv = new PendingVacation("delete"); // state = DELETE_IDLE

        // IDLE: employee in message
        VacationDeletionService.WizardResult r1 = service.process(pv, "delete vacation for Alice Smith",
                employees, allRecords);
        assertFalse(r1.confirmed());
        assertFalse(r1.cancelled());
        assertEquals(WizardState.DELETE_NEED_START, pv.getState());
        assertEquals("Alice Smith", pv.getEmployeeName());

        // DELETE_NEED_START
        VacationDeletionService.WizardResult r2 = service.process(pv, "2026-06-01", employees, allRecords);
        assertFalse(r2.confirmed());
        assertEquals(WizardState.DELETE_NEED_END, pv.getState());
        assertEquals(LocalDate.of(2026, 6, 1), pv.getStartDate());

        // DELETE_NEED_END: entire covered range
        VacationDeletionService.WizardResult r3 = service.process(pv, "2026-06-05", employees, allRecords);
        assertFalse(r3.confirmed());
        assertEquals(WizardState.DELETE_CONFIRM, pv.getState());
        assertEquals(5.0, pv.getDays(), 0.001);

        // DELETE_CONFIRM: yes
        VacationDeletionService.WizardResult r4 = service.process(pv, "yes", employees, allRecords);
        assertTrue(r4.confirmed());
        assertFalse(r4.cancelled());
        assertEquals(WizardState.DELETED, pv.getState());
    }

    @Test
    void fullWizard_confirmWithY() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "2026-06-01", employees, allRecords);
        service.process(pv, "2026-06-05", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "y", employees, allRecords);
        assertTrue(r.confirmed());
    }

    @Test
    void fullWizard_confirmWithConfirm() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "2026-06-01", employees, allRecords);
        service.process(pv, "2026-06-05", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "confirm", employees, allRecords);
        assertTrue(r.confirmed());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // IDLE → DELETE_NEED_EMP path
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void idle_noEmployeeInMessage_asksForEmployee() {
        PendingVacation pv = new PendingVacation("delete");
        VacationDeletionService.WizardResult r = service.process(pv, "delete vacation", employees, allRecords);
        assertFalse(r.confirmed());
        assertEquals(WizardState.DELETE_NEED_EMP, pv.getState());
    }

    @Test
    void needEmp_validEmployee_advancesToNeedStart() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation", employees, allRecords); // → DELETE_NEED_EMP

        VacationDeletionService.WizardResult r = service.process(pv, "Alice Smith", employees, allRecords);
        assertEquals(WizardState.DELETE_NEED_START, pv.getState());
        assertEquals("Alice Smith", pv.getEmployeeName());
    }

    @Test
    void needEmp_unknownEmployee_staysInNeedEmp() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "Zephyr Unknown", employees, allRecords);
        assertEquals(WizardState.DELETE_NEED_EMP, pv.getState());
        assertFalse(r.confirmed());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Pre-seeded employee (employee-role — skip DELETE_NEED_EMP)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void preSeededEmployee_deleteNeedStartIsFirstStep() {
        PendingVacation pv = new PendingVacation("delete");
        pv.setEmployeeName("Alice Smith");
        pv.setState(WizardState.DELETE_NEED_START);

        VacationDeletionService.WizardResult r = service.process(pv, "2026-06-01", employees, allRecords);
        assertEquals(WizardState.DELETE_NEED_END, pv.getState());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE_NEED_START — invalid dates
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void needStart_invalidDateFormat_staysInNeedStart() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "01/06/2026", employees, allRecords);
        assertEquals(WizardState.DELETE_NEED_START, pv.getState());
        assertTrue(r.reply().contains("YYYY-MM-DD"));
    }

    @Test
    void needStart_naturalLanguage_staysInNeedStart() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "last Monday", employees, allRecords);
        assertEquals(WizardState.DELETE_NEED_START, pv.getState());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE_NEED_END — validation
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void needEnd_endBeforeStart_errorMessage() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "2026-06-03", employees, allRecords); // start June 3

        VacationDeletionService.WizardResult r = service.process(pv, "2026-06-02", employees, allRecords);
        assertEquals(WizardState.DELETE_NEED_END, pv.getState());
        assertTrue(r.reply().toLowerCase().contains("end date"));
    }

    @Test
    void needEnd_dateNotCovered_deletionAborted() {
        // Alice has leave June 1-5; try to delete June 8 (not covered)
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "2026-06-08", employees, allRecords); // start = Mon Jun 8

        VacationDeletionService.WizardResult r = service.process(pv, "2026-06-10", employees, allRecords);
        // Not covered → cancelled
        assertTrue(r.cancelled());
        assertEquals(WizardState.DELETE_CANCELLED, pv.getState());
        assertTrue(r.reply().toLowerCase().contains("cannot delete") ||
                   r.reply().toLowerCase().contains("not covered"));
    }

    @Test
    void needEnd_singleDay_matchesStart() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "2026-06-01", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "2026-06-01", employees, allRecords);
        assertEquals(WizardState.DELETE_CONFIRM, pv.getState());
        assertEquals(1.0, pv.getDays(), 0.001);
    }

    @Test
    void needEnd_weekendRange_noWorkingDays_error() {
        // Even if employee has coverage, a weekend-only range has 0 working days
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "2026-06-06", employees, allRecords); // Saturday

        VacationDeletionService.WizardResult r = service.process(pv, "2026-06-07", employees, allRecords);
        // 0 working days
        assertEquals(WizardState.DELETE_NEED_END, pv.getState());
        assertTrue(r.reply().toLowerCase().contains("working day"));
    }

    @Test
    void needEnd_invalidDateFormat_staysInNeedEnd() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "2026-06-01", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "not-a-date", employees, allRecords);
        assertEquals(WizardState.DELETE_NEED_END, pv.getState());
    }

    @Test
    void needEnd_partialCoverage_abortedOnFirstUncoveredDay() {
        // Alice has June 1-5. Try to delete June 1-8 (Jun 8 not covered)
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "2026-06-01", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "2026-06-08", employees, allRecords);
        assertTrue(r.cancelled());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Warning for single-day inside a longer block
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void needEnd_singleDayInsideLongerBlock_includesNote() {
        // Alice's record is June 1-5. Delete single day June 3.
        // The warning should mention the larger block.
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "2026-06-03", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "2026-06-03", employees, allRecords);
        assertEquals(WizardState.DELETE_CONFIRM, pv.getState());
        // Should include "Note:" about the longer block
        assertTrue(r.reply().contains("Note:") || r.reply().contains("block"),
            "Expected a warning about the longer block. Reply: " + r.reply());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Cancel at each stage
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void cancel_atDeleteNeedEmp_wizardCancelled() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "cancel", employees, allRecords);
        assertTrue(r.cancelled());
        assertEquals(WizardState.DELETE_CANCELLED, pv.getState());
    }

    @Test
    void cancel_atDeleteNeedStart_wizardCancelled() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "abort", employees, allRecords);
        assertTrue(r.cancelled());
    }

    @Test
    void cancel_atDeleteNeedEnd_wizardCancelled() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "2026-06-01", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "stop", employees, allRecords);
        assertTrue(r.cancelled());
    }

    @Test
    void cancel_atDeleteConfirm_no_cancelsWizard() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);
        service.process(pv, "2026-06-01", employees, allRecords);
        service.process(pv, "2026-06-05", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "no", employees, allRecords);
        assertTrue(r.cancelled());
    }

    @Test
    void cancel_atDeleteNeedStart_quit_cancels() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "quit", employees, allRecords);
        assertTrue(r.cancelled());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Negative — no records for employee
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void needEnd_noRecordsForEmployee_dateNotCovered() {
        // Carol has no records — any date will be uncovered
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Carol Nguyen", employees, allRecords);
        service.process(pv, "2026-06-01", employees, allRecords);

        VacationDeletionService.WizardResult r = service.process(pv, "2026-06-01", employees, allRecords);
        // Jun 1 is a weekday but Carol has no records → not covered
        assertTrue(r.cancelled());
    }

    @Test
    void idle_emptyRecordList_doesNotThrow() {
        PendingVacation pv = new PendingVacation("delete");
        assertDoesNotThrow(() -> service.process(pv, "delete vacation for Alice Smith",
                employees, Collections.emptyList()));
    }

    @Test
    void needEnd_emptyRecordList_dateCoveredCheckFails() {
        PendingVacation pv = new PendingVacation("delete");
        service.process(pv, "delete vacation for Alice Smith", employees, Collections.emptyList());
        service.process(pv, "2026-06-01", employees, Collections.emptyList());

        VacationDeletionService.WizardResult r = service.process(pv, "2026-06-01", employees, Collections.emptyList());
        assertTrue(r.cancelled());
    }
}
