package za.co.fnb.dcre.mrr.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B1: first-wins intra-file dedup and its job-execution-context CSV round-trip. */
class IntraFileDuplicatesTest {

    @Test
    void firstOccurrenceWinsLaterOccurrencesAreFlagged() {
        // seq1 (A,CREATE) first, seq2 (B,CREATE) first, seq3 (A,CREATE) is the later duplicate
        IntraFileDuplicates dups = IntraFileDuplicates.scan(List.of(
                new IntraFileDuplicates.Row(1, "MREF-A", "CREATE"),
                new IntraFileDuplicates.Row(2, "MREF-B", "CREATE"),
                new IntraFileDuplicates.Row(3, "MREF-A", "CREATE")));
        assertFalse(dups.isDuplicate(1), "first occurrence mints");
        assertFalse(dups.isDuplicate(2), "distinct ref mints");
        assertTrue(dups.isDuplicate(3), "later occurrence flagged");
    }

    @Test
    void actionCodeIsPartOfTheDedupKey() {
        // same ref, different action = two distinct mandate operations, neither a duplicate
        IntraFileDuplicates dups = IntraFileDuplicates.scan(List.of(
                new IntraFileDuplicates.Row(1, "MREF-A", "CREATE"),
                new IntraFileDuplicates.Row(2, "MREF-A", "CANCEL")));
        assertFalse(dups.isDuplicate(1));
        assertFalse(dups.isDuplicate(2));
    }

    @Test
    void csvRoundTripPreservesTheDuplicateSet() {
        IntraFileDuplicates dups = IntraFileDuplicates.scan(List.of(
                new IntraFileDuplicates.Row(1, "MREF-A", "CREATE"),
                new IntraFileDuplicates.Row(2, "MREF-A", "CREATE"),
                new IntraFileDuplicates.Row(3, "MREF-A", "CREATE")));
        assertEquals("2,3", dups.toCsv(), "ascending later-duplicate sequences");
        IntraFileDuplicates parsed = IntraFileDuplicates.parse(dups.toCsv());
        assertTrue(parsed.isDuplicate(2));
        assertTrue(parsed.isDuplicate(3));
        assertFalse(parsed.isDuplicate(1));
    }

    @Test
    void blankOrNullCsvIsNoDuplicates() {
        assertEquals("", IntraFileDuplicates.scan(List.of()).toCsv());
        assertFalse(IntraFileDuplicates.parse(null).isDuplicate(1));
        assertFalse(IntraFileDuplicates.parse("").isDuplicate(1));
    }
}
