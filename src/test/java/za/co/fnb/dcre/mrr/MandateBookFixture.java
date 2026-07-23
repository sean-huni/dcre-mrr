package za.co.fnb.dcre.mrr;

import za.co.fnb.dcre.platform.files.MandateLayouts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-builds fixed-width instruction book lines against MandateLayouts
 * itself (A-61 SYNTHETIC-CONTRACT): every built line is round-tripped through
 * MandateLayouts.slice, so any drift between these width tables and the
 * platform layout fails the fixture loudly instead of silently skewing tests.
 */
public final class MandateBookFixture {

    public static final String CLIENT = "FNBCC01";
    public static final String ORIGINAL_NAME = "FNBCC01_MNDT2026072212000001.txt";
    public static final String CREATED_TS = "20260722083000";
    public static final String BUSINESS_DATE = "20260722";
    public static final String AMOUNT_RAW = "000000000150000"; // 1500.00 at scale 2

    private MandateBookFixture() {
    }

    public static String header(String destinationId, int entryCount) {
        String line = "MHDR"                                    // record_type 4
                + "FNB1"                                        // sender_id 4
                + "MB"                                          // file_type 2
                + CREATED_TS                                    // created_ts 14
                + " 1"                                          // layout_version 2
                + " ".repeat(13)                                // filler_1 13
                + " ".repeat(13)                                // created_short 13
                + String.format("%014d", entryCount)            // entry_count 14
                + String.format("%-7s", destinationId)          // destination_id 7
                + " ".repeat(28)                                // filler_2 28
                + BUSINESS_DATE;                                // business_date 8
        verify(line.length() == MandateLayouts.HEADER.length(), "header length " + line.length());
        verify(MandateLayouts.HEADER.slice(line, "destination_id").strip().equals(destinationId),
                "destination_id round-trip");
        verify(Integer.parseInt(MandateLayouts.HEADER.slice(line, "entry_count").strip()) == entryCount,
                "entry_count round-trip");
        return line;
    }

    public static String detail(int sequence, String actionCode, String mandateRef) {
        String line = "MD"                                      // record_type 2
                + String.format("%06d", sequence)               // sequence 6
                + actionCode                                    // action_code 3
                + String.format("%-35s", mandateRef)            // mandate_ref 35
                + String.format("%-14s", "CTR" + sequence)      // contract_ref 14
                + String.format("%-32s", "62000000010")         // creditor_account 32
                + String.format("%-32s", "6200000002" + sequence) // debtor_account 32
                + String.format("%-16s", "250655")              // debtor_branch 16
                + String.format("%-70s", "JANE DOE " + sequence) // debtor_name 70
                + "ZAR"                                         // currency 3
                + AMOUNT_RAW                                    // max_collection_amount 15
                + "MNTH"                                        // frequency 4
                + "01"                                          // collection_day 2
                + "20260801"                                    // start_date 8
                + " ".repeat(8)                                 // expiry_date 8 (open-ended)
                + " ".repeat(35);                               // mndt_req_id 35 (DCRE mints)
        verify(line.length() == MandateLayouts.DETAIL.length(), "detail length " + line.length());
        verify(MandateLayouts.DETAIL.slice(line, "action_code").equals(actionCode), "action_code round-trip");
        verify(MandateLayouts.DETAIL.slice(line, "mandate_ref").strip().equals(mandateRef), "mandate_ref round-trip");
        verify(MandateLayouts.DETAIL.slice(line, "max_collection_amount").equals(AMOUNT_RAW), "amount round-trip");
        return line;
    }

    public static Path book(Path dir, String fileName, String header, List<String> details) throws IOException {
        Files.createDirectories(dir);
        List<String> all = new ArrayList<>();
        all.add(header);
        all.addAll(details);
        Path file = dir.resolve(fileName);
        Files.writeString(file, String.join("\n", all) + "\n", StandardCharsets.ISO_8859_1);
        return file.toAbsolutePath();
    }

    private static void verify(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("fixture drifted from MandateLayouts: " + what);
        }
    }
}
