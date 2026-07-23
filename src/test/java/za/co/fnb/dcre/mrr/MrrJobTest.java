package za.co.fnb.dcre.mrr;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ingest suite over the real job + CockroachDB (Testcontainers): positive
 * parse of a generated instruction book, malformed-header whole-file fatal,
 * byte-verbatim replay idempotency and deterministic MndtReqId minting.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MrrJobTest {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    // Own exchange root per suite run: the seam name falls back to
    // local-mrr-<executionId> and StagedWrite (R-24) is exists->skip, so a
    // prior run's file left under a shared build dir would mask this run's
    // verdict. A fresh temp dir isolates each run.
    static final Path EXCHANGE_ROOT;

    static {
        CRDB.start();
        try {
            EXCHANGE_ROOT = Files.createTempDirectory("mrr-seam-exchange-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.exchange-root", EXCHANGE_ROOT::toString);
    }

    @Autowired
    Job mrrJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    static final UUID ARRIVAL = UUID.randomUUID();
    static final Path BOOK_DIR = Path.of("build/test-books");

    void clearOutcomes() throws IOException {
        Path outcomes = EXCHANGE_ROOT.resolve("outcomes");
        if (Files.isDirectory(outcomes)) {
            try (var entries = Files.newDirectoryStream(outcomes)) {
                for (Path entry : entries) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    static Path standardBook() throws IOException {
        return MandateBookFixture.book(BOOK_DIR, MandateBookFixture.ORIGINAL_NAME,
                MandateBookFixture.header(MandateBookFixture.CLIENT, 3),
                List.of(MandateBookFixture.detail(1, "CRE", "MREF-CRE-001"),
                        MandateBookFixture.detail(2, "AMD", "MREF-AMD-002"),
                        MandateBookFixture.detail(3, "CAN", "MREF-CAN-003")));
    }

    JobParameters params(UUID arrival, Path book, String attempt) {
        JobParametersBuilder builder = new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .addString("input.file", book.toString(), false)
                .addString("original.name", MandateBookFixture.ORIGINAL_NAME, false);
        if (attempt != null) {
            builder.addString("attempt", attempt, true);
        }
        return builder.toJobParameters();
    }

    List<String> mintedProjection(UUID arrival) {
        return jdbc.queryForList("""
                SELECT sequence || '|' || action_code || '|' || mandate_ref || '|' || mndt_req_id
                FROM mandate_request_entry WHERE arrival_id=? ORDER BY sequence""", String.class, arrival);
    }

    @Test
    @Order(1)
    void parsesGeneratedBookIntoSpine() throws Exception {
        clearOutcomes();
        JobExecution run = jobOperator.start(mrrJob, params(ARRIVAL, standardBook(), null));
        assertEquals(BatchStatus.COMPLETED, run.getStatus());

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_header WHERE arrival_id=?", Integer.class, ARRIVAL));
        assertEquals(MandateBookFixture.CLIENT, jdbc.queryForObject(
                "SELECT destination_id FROM mandate_request_header WHERE arrival_id=?", String.class, ARRIVAL));
        assertEquals(3, jdbc.queryForObject(
                "SELECT entry_count FROM mandate_request_header WHERE arrival_id=?", Integer.class, ARRIVAL));

        assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=?", Integer.class, ARRIVAL));
        assertEquals(List.of("CREATE", "AMEND", "CANCEL"), jdbc.queryForList(
                "SELECT action_code FROM mandate_request_entry WHERE arrival_id=? ORDER BY sequence",
                String.class, ARRIVAL), "action codes canonicalize CRE|AMD|CAN");
        assertEquals(List.of("RECEIVED", "RECEIVED", "RECEIVED"), jdbc.queryForList(
                "SELECT spine_state FROM mandate_request_entry WHERE arrival_id=? ORDER BY sequence",
                String.class, ARRIVAL), "MRR leaves spine_state at its RECEIVED default");

        BigDecimal amount = jdbc.queryForObject(
                "SELECT max_collection_amount FROM mandate_request_entry WHERE arrival_id=? AND sequence=1",
                BigDecimal.class, ARRIVAL);
        assertEquals(new BigDecimal(MandateBookFixture.AMOUNT_RAW).movePointLeft(2).stripTrailingZeros(),
                amount.stripTrailingZeros(), "amount parsed via the single MoneyText converter at scale 2");

        assertEquals(3, jdbc.queryForObject(
                "SELECT count(DISTINCT mndt_req_id) FROM mandate_request_entry WHERE arrival_id=?",
                Integer.class, ARRIVAL), "every entry mints its own MndtReqId");
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=? AND length(mndt_req_id) != 35",
                Integer.class, ARRIVAL), "MndtReqId is the 35-char ISO 20022 shape");

        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("JOB_NAME") == null,
                "seam assertion requires JOB_NAME absent from the environment");
        Path seam = EXCHANGE_ROOT.resolve("outcomes").resolve("local-mrr-" + run.getId());
        assertTrue(Files.exists(seam), "expected self-describing seam file at " + seam);
        assertEquals(List.of("BUSINESS_ACCEPTED"), Files.readAllLines(seam));
    }

    @Test
    @Order(2)
    void rerunSameIdentityRefusesWithoutDuplicate() throws Exception {
        // R-05/R-16: same identifying parameters = same JobInstance; a completed
        // instance refuses a second run and the spine stays exactly 3 rows.
        Path book = standardBook();
        var thrown = assertThrows(Exception.class,
                () -> jobOperator.start(mrrJob, params(ARRIVAL, book, null)));
        assertTrue(thrown.getClass().getSimpleName().contains("JobInstanceAlreadyComplete")
                        || String.valueOf(thrown.getMessage()).contains("already"),
                "unexpected: " + thrown);
        assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=?", Integer.class, ARRIVAL));
    }

    @Test
    @Order(3)
    void byteVerbatimReplayIsIdempotentAndRemintsIdenticalIds() throws Exception {
        // R-07: a fresh JobInstance re-processing the byte-identical book for
        // the same arrival rewrites identical rows through the guarded upsert;
        // the deterministic mint yields the SAME MndtReqId per entry, so the
        // spine neither duplicates nor drifts.
        List<String> before = mintedProjection(ARRIVAL);
        assertEquals(3, before.size(), "precondition: order(1) ingested the book");

        JobExecution replay = jobOperator.start(mrrJob, params(ARRIVAL, standardBook(), "2"));
        assertEquals(BatchStatus.COMPLETED, replay.getStatus());

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_header WHERE arrival_id=?", Integer.class, ARRIVAL));
        assertEquals(before, mintedProjection(ARRIVAL),
                "byte-verbatim replay must be row-identical, MndtReqIds included");
        assertEquals(jdbc.queryForObject(
                        "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=?", Integer.class, ARRIVAL),
                jdbc.queryForObject(
                        "SELECT count(DISTINCT (arrival_id, sequence)) FROM mandate_request_entry WHERE arrival_id=?",
                        Integer.class, ARRIVAL),
                "zero-duplicate audit: count == distinct business identity");
    }

    @Test
    @Order(4)
    void truncatedHeaderIsWholeFileFatal() throws Exception {
        clearOutcomes();
        UUID arrival = UUID.randomUUID();
        String truncated = MandateBookFixture.header(MandateBookFixture.CLIENT, 1).substring(0, 80);
        Path book = MandateBookFixture.book(BOOK_DIR, "FNBCC01_MNDT2026072212000002.txt",
                truncated, List.of(MandateBookFixture.detail(1, "CRE", "MREF-FATAL-001")));

        JobExecution run = jobOperator.start(mrrJob, params(arrival, book, null));
        assertEquals(BatchStatus.COMPLETED, run.getStatus(), "FILE_FATAL is a business verdict, not a crash");
        assertTrue(run.getExecutionContext().getString("fileFatalReason").contains("header shorter"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=?", Integer.class, arrival),
                "no entries persisted for a file-fatal book");

        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("JOB_NAME") == null,
                "seam assertion requires JOB_NAME absent from the environment");
        Path seam = EXCHANGE_ROOT.resolve("outcomes").resolve("local-mrr-" + run.getId());
        assertTrue(Files.exists(seam), "expected self-describing seam file at " + seam);
        assertEquals(List.of("BUSINESS_FILE_FATAL"), Files.readAllLines(seam));
    }

    @Test
    @Order(5)
    void nonNumericEntryCountIsWholeFileFatal() throws Exception {
        // entry_count occupies header columns [52,66) per MandateLayouts.HEADER
        UUID arrival = UUID.randomUUID();
        String header = MandateBookFixture.header(MandateBookFixture.CLIENT, 1);
        String mutated = header.substring(0, 52) + "XXXXXXXXXXXXXX" + header.substring(66);
        Path book = MandateBookFixture.book(BOOK_DIR, "FNBCC01_MNDT2026072212000003.txt",
                mutated, List.of(MandateBookFixture.detail(1, "CRE", "MREF-FATAL-002")));

        JobExecution run = jobOperator.start(mrrJob, params(arrival, book, null));
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertTrue(run.getExecutionContext().getString("fileFatalReason").contains("entry_count is not numeric"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=?", Integer.class, arrival));
    }

    @Test
    @Order(7)
    void intraFileDuplicateRefFirstWinsAndReplaysDeterministically() throws Exception {
        // B1(a): two rows sharing (mandate_ref, action_code) in ONE book must
        // not poison the global mndt_req_id UNIQUE: the FIRST occurrence (by
        // sequence) mints, later occurrences land with NULL id + dup_in_file
        // for MRV's FAIL_DUPLICATE_REF verdict, deterministically across replays.
        UUID arrival = UUID.randomUUID();
        Path book = MandateBookFixture.book(BOOK_DIR, "FNBCC01_MNDT2026072212000005.txt",
                MandateBookFixture.header(MandateBookFixture.CLIENT, 3),
                List.of(MandateBookFixture.detail(1, "CRE", "MREF-DUP-A"),
                        MandateBookFixture.detail(2, "CRE", "MREF-DUP-B"),
                        MandateBookFixture.detail(3, "CRE", "MREF-DUP-A")));

        JobExecution run = jobOperator.start(mrrJob, params(arrival, book, null));
        assertEquals(BatchStatus.COMPLETED, run.getStatus(),
                "an intra-file duplicate is DATA for MRV, never a technical crash (B1)");

        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT sequence, mndt_req_id, dup_in_file FROM mandate_request_entry WHERE arrival_id=? ORDER BY sequence",
                arrival);
        assertEquals(3, rows.size());
        assertEquals(35, String.valueOf(rows.get(0).get("mndt_req_id")).length(), "first occurrence mints");
        assertEquals(false, rows.get(0).get("dup_in_file"));
        assertEquals(35, String.valueOf(rows.get(1).get("mndt_req_id")).length(), "distinct ref mints");
        assertEquals(false, rows.get(1).get("dup_in_file"));
        assertTrue(rows.get(2).get("mndt_req_id") == null, "later duplicate carries NULL id");
        assertEquals(true, rows.get(2).get("dup_in_file"), "later duplicate flagged for MRV");

        List<String> before = dupProjection(arrival);
        JobExecution replay = jobOperator.start(mrrJob, params(arrival, book, "2"));
        assertEquals(BatchStatus.COMPLETED, replay.getStatus());
        assertEquals(before, dupProjection(arrival),
                "first-wins dedup must be deterministic across byte-verbatim replays");
    }

    List<String> dupProjection(UUID arrival) {
        return jdbc.queryForList("""
                SELECT sequence || '|' || COALESCE(mndt_req_id, '-') || '|' || dup_in_file
                FROM mandate_request_entry WHERE arrival_id=? ORDER BY sequence""", String.class, arrival);
    }

    @Test
    @Order(8)
    void crossArrivalResubmitCollisionIsBusinessFatalNotTechCrash() throws Exception {
        // B1(b): a byte-identical book resubmitted under a NEW arrival identity
        // (same header, same refs -> same deterministic mint) must convert the
        // mndt_req_id unique violation into a BUSINESS file-fatal (MIR NACK
        // path), never a raw technical crash loop. AGT R-16 quarantine is the
        // primary guard upstream; this is the belt.
        clearOutcomes();
        UUID first = UUID.randomUUID();
        Path bookP = MandateBookFixture.book(BOOK_DIR, "FNBCC01_MNDT2026072212000006.txt",
                MandateBookFixture.header(MandateBookFixture.CLIENT, 1),
                List.of(MandateBookFixture.detail(1, "CRE", "MREF-COLL-X")));
        assertEquals(BatchStatus.COMPLETED, jobOperator.start(mrrJob, params(first, bookP, null)).getStatus());

        // Isolate the resubmit's seam from the first arrival's. MRR now wires the
        // platform persistent JDBC JobRepository (MRR_BATCH_ tables), so
        // JobExecution.getId() is MONOTONIC and persistent, not the constant 1 of
        // Batch 6's ResourcelessJobRepository: the two runs already get DISTINCT
        // local-mrr-<id> seam names, so this clearOutcomes() is no longer REQUIRED
        // to avoid an id collision. It stays as defensive isolation (production
        // names the seam by the unique JOB_NAME, so it is moot there).
        clearOutcomes();
        UUID resubmit = UUID.randomUUID();
        Path bookQ = MandateBookFixture.book(BOOK_DIR, "FNBCC01_MNDT2026072212000007.txt",
                MandateBookFixture.header(MandateBookFixture.CLIENT, 1),
                List.of(MandateBookFixture.detail(1, "CRE", "MREF-COLL-X")));
        JobExecution run = jobOperator.start(mrrJob, params(resubmit, bookQ, null));

        assertEquals(BatchStatus.COMPLETED, run.getStatus(),
                "cross-arrival collision is a business verdict, not a TECH failure");
        assertTrue(run.getExecutionContext().getString("fileFatalReason").contains("MndtReqId collision"),
                "reason must name the collision");
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=?", Integer.class, resubmit),
                "colliding arrival persists nothing");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=?", Integer.class, first),
                "original arrival untouched");

        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("JOB_NAME") == null,
                "seam assertion requires JOB_NAME absent from the environment");
        Path seam = EXCHANGE_ROOT.resolve("outcomes").resolve("local-mrr-" + run.getId());
        assertTrue(Files.exists(seam), "expected self-describing seam file at " + seam);
        assertEquals(List.of("BUSINESS_FILE_FATAL"), Files.readAllLines(seam));
    }

    @Test
    @Order(9)
    void crossArrivalPartialOverlapCollisionIsBusinessFatalNotTechCrash() throws Exception {
        // B1b partial overlap: a resubmit under a NEW arrival that shares the
        // msg_id (byte-identical header) but reorders/changes content so only a
        // LATER row collides with a prior arrival must still be caught at INGEST
        // as a business FILE_FATAL, never slip the pre-flight and die at the DAO
        // belt as a TECH crash. Exercises the all-first-occurrence-ids pre-flight.
        clearOutcomes();
        UUID first = UUID.randomUUID();
        Path bookP = MandateBookFixture.book(BOOK_DIR, "FNBCC01_MNDT2026072212000008.txt",
                MandateBookFixture.header(MandateBookFixture.CLIENT, 2),
                List.of(MandateBookFixture.detail(1, "CRE", "MREF-OV-P1"),
                        MandateBookFixture.detail(2, "CRE", "MREF-OV-P2")));
        assertEquals(BatchStatus.COMPLETED, jobOperator.start(mrrJob, params(first, bookP, null)).getStatus());

        // Same header bytes -> same msg_id; row 1 is a NEW ref (no collision),
        // only row 2 (MREF-OV-P2) collides with arrival-1's row 2, so a
        // first-row-only pre-flight would miss it and hit the DAO poison-pill.
        clearOutcomes();
        UUID resubmit = UUID.randomUUID();
        Path bookQ = MandateBookFixture.book(BOOK_DIR, "FNBCC01_MNDT2026072212000009.txt",
                MandateBookFixture.header(MandateBookFixture.CLIENT, 2),
                List.of(MandateBookFixture.detail(1, "CRE", "MREF-OV-P3"),
                        MandateBookFixture.detail(2, "CRE", "MREF-OV-P2")));
        JobExecution run = jobOperator.start(mrrJob, params(resubmit, bookQ, null));

        assertEquals(BatchStatus.COMPLETED, run.getStatus(),
                "a later-row cross-arrival collision is a business verdict, not a TECH failure");
        assertTrue(run.getExecutionContext().getString("fileFatalReason").contains("MndtReqId collision"),
                "reason must name the collision");
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=?", Integer.class, resubmit),
                "colliding arrival persists nothing");
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=?", Integer.class, first),
                "original arrival untouched");

        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("JOB_NAME") == null,
                "seam assertion requires JOB_NAME absent from the environment");
        Path seam = EXCHANGE_ROOT.resolve("outcomes").resolve("local-mrr-" + run.getId());
        assertTrue(Files.exists(seam), "expected self-describing seam file at " + seam);
        assertEquals(List.of("BUSINESS_FILE_FATAL"), Files.readAllLines(seam));
    }

    @Test
    @Order(6)
    void declaredCountMismatchIsWholeFileFatal() throws Exception {
        UUID arrival = UUID.randomUUID();
        Path book = MandateBookFixture.book(BOOK_DIR, "FNBCC01_MNDT2026072212000004.txt",
                MandateBookFixture.header(MandateBookFixture.CLIENT, 3),
                List.of(MandateBookFixture.detail(1, "CRE", "MREF-FATAL-003"),
                        MandateBookFixture.detail(2, "CRE", "MREF-FATAL-004")));

        JobExecution run = jobOperator.start(mrrJob, params(arrival, book, null));
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertTrue(run.getExecutionContext().getString("fileFatalReason").contains("entry_count=3 but file has 2"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=?", Integer.class, arrival));
    }
}
