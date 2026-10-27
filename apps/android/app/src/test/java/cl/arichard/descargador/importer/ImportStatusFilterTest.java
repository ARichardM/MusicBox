package cl.arichard.descargador.importer;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImportStatusFilterTest {
    @Test public void eachSongBelongsToOneStatusAndTheAllFilter() {
        for (String state : new String[]{"idle", "searching", "auto", "review", "none", "error"}) {
            for (boolean chosen : new boolean[]{false, true}) {
                int groups = 0;
                for (ImportStatusFilter filter : new ImportStatusFilter[]{ImportStatusFilter.READY,
                        ImportStatusFilter.REVIEW, ImportStatusFilter.PENDING}) if (filter.matches(state, chosen)) groups++;
                assertEquals(state, 1, groups);
                assertTrue(ImportStatusFilter.ALL.matches(state, chosen));
            }
        }
    }

    @Test public void manualAndAutomaticChoicesAppearTogether() {
        assertTrue(ImportStatusFilter.READY.matches("auto", true));
        assertTrue(ImportStatusFilter.READY.matches("review", true));
        assertFalse(ImportStatusFilter.REVIEW.matches("review", true));
    }

    @Test public void deselectingAnAutomaticChoiceNeedsReview() {
        assertTrue(ImportStatusFilter.REVIEW.matches("auto", false));
        assertFalse(ImportStatusFilter.PENDING.matches("auto", false));
    }

    @Test public void failedEmptyAndInterruptedSearchesStayPending() {
        for (String state : new String[]{"idle", "searching", "none", "error"}) {
            assertTrue(ImportStatusFilter.PENDING.matches(state, false));
            assertFalse(ImportStatusFilter.READY.matches(state, false));
            assertFalse(ImportStatusFilter.REVIEW.matches(state, false));
        }
    }
}
