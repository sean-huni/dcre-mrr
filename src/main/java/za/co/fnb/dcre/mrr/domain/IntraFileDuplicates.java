package za.co.fnb.dcre.mrr.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * First-wins intra-file dedup of the mandate spine (B1). Within ONE instruction
 * book the deterministic MndtReqId collapses to the (mandate_ref, action_code)
 * pair (client + msg_id are file-constant), so two rows sharing it would mint the
 * IDENTICAL id and poison the global uq_man_req_entry_mndt_req_id UNIQUE mid-chunk.
 * Instead the FIRST occurrence (lowest sequence) mints normally and every LATER
 * occurrence is persisted with a NULL mndt_req_id and dup_in_file=true, so MRV
 * (T4) emits FAIL_DUPLICATE_REF for it (platform-model MandateOutcome).
 *
 * <p>Computed single-threaded at ingest (the only global file view) and ferried to
 * the partitioned entry writer through the job execution context as a compact CSV
 * of the later-occurrence sequences. Ordering by ascending sequence keeps the
 * winner stable across byte-verbatim replays (deterministic).
 */
public final class IntraFileDuplicates {

    /** A detail row's dedup identity: sequence plus the (mandate_ref, action_code) the mint collapses to. */
    public record Row(int sequence, String mandateRef, String actionCode) {
    }

    private record Key(String mandateRef, String actionCode) {
    }

    private final SortedSet<Integer> laterSequences;

    private IntraFileDuplicates(SortedSet<Integer> laterSequences) {
        this.laterSequences = laterSequences;
    }

    /** Later occurrences (by ascending sequence) of any repeated (mandate_ref, action_code). */
    public static IntraFileDuplicates scan(List<Row> rowsAscending) {
        Set<Key> seen = new HashSet<>();
        SortedSet<Integer> later = new TreeSet<>();
        for (Row row : rowsAscending) {
            if (!seen.add(new Key(row.mandateRef(), row.actionCode()))) {
                later.add(row.sequence());
            }
        }
        return new IntraFileDuplicates(later);
    }

    /** Reconstructs the set from the job-execution-context CSV (null/blank -> none). */
    public static IntraFileDuplicates parse(String csv) {
        SortedSet<Integer> later = new TreeSet<>();
        if (csv != null && !csv.isBlank()) {
            for (String part : csv.split(",")) {
                later.add(Integer.parseInt(part.strip()));
            }
        }
        return new IntraFileDuplicates(later);
    }

    public boolean isDuplicate(int sequence) {
        return laterSequences.contains(sequence);
    }

    public String toCsv() {
        return laterSequences.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
