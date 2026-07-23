package za.co.fnb.dcre.mrr.bdd;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fnb.dcre.mrr.MandateBookFixture;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step definitions for the MRR boundary reader. Books are generated per
 * scenario from MandateBookFixture (widths pinned to MandateLayouts, A-61).
 */
public class MrrSteps {

    static final Path BOOK_DIR = Path.of("build/bdd-books");

    @Autowired
    Job mrrJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    UUID arrival;
    Path inputFile;
    JobExecution execution;
    List<String> firstRunMndtReqIds;

    @Given("the book reader receives an instruction book with a create, an amend and a cancel row")
    public void standardBook() throws Exception {
        arrival = UUID.randomUUID();
        inputFile = MandateBookFixture.book(BOOK_DIR, "std-" + arrival + ".txt",
                MandateBookFixture.header(MandateBookFixture.CLIENT, 3),
                List.of(MandateBookFixture.detail(1, "CRE", "BDD-CRE-" + shortId()),
                        MandateBookFixture.detail(2, "AMD", "BDD-AMD-" + shortId()),
                        MandateBookFixture.detail(3, "CAN", "BDD-CAN-" + shortId())));
    }

    @Given("the book reader receives an instruction book with a truncated header")
    public void truncatedHeaderBook() throws Exception {
        arrival = UUID.randomUUID();
        inputFile = MandateBookFixture.book(BOOK_DIR, "trunc-" + arrival + ".txt",
                MandateBookFixture.header(MandateBookFixture.CLIENT, 1).substring(0, 80),
                List.of(MandateBookFixture.detail(1, "CRE", "BDD-TRUNC-" + shortId())));
    }

    @Given("the book reader receives an instruction book declaring {int} entries but carrying {int}")
    public void wrongCountBook(int declared, int actual) throws Exception {
        arrival = UUID.randomUUID();
        List<String> details = new java.util.ArrayList<>();
        for (int i = 1; i <= actual; i++) {
            details.add(MandateBookFixture.detail(i, "CRE", "BDD-CNT-" + i + "-" + shortId()));
        }
        inputFile = MandateBookFixture.book(BOOK_DIR, "count-" + arrival + ".txt",
                MandateBookFixture.header(MandateBookFixture.CLIENT, declared), details);
    }

    @When("the MRR job runs")
    public void mrrJobRuns() throws Exception {
        execution = jobOperator.start(mrrJob, params(null));
        firstRunMndtReqIds = mndtReqIds();
    }

    @When("the MRR job runs again for the same arrival")
    public void mrrJobRunsAgain() throws Exception {
        // new attempt parameter: a fresh JobInstance re-processing the same
        // arrival exercises the ON CONFLICT upsert path, not instance refusal
        execution = jobOperator.start(mrrJob, params("2"));
    }

    @Then("the job completes with a clean business verdict")
    public void jobCompletesClean() {
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertFalse(execution.getExecutionContext().containsKey("fileFatalReason"),
                "no file-fatal reason expected");
    }

    @Then("the book is rejected file-fatally with a reason containing {string}")
    public void rejectedFileFatally(String reasonPart) {
        // FILE_FATAL is a business verdict, not a crash: the job COMPLETES
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        String reason = execution.getExecutionContext().getString("fileFatalReason");
        assertTrue(reason.contains(reasonPart),
                "expected file-fatal reason containing '" + reasonPart + "' but was: " + reason);
    }

    @Then("the spine holds one header row and {int} entry rows for the arrival")
    public void spineHoldsRows(int entries) {
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_header WHERE arrival_id=?", Integer.class, arrival));
        assertEquals(entries, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=?", Integer.class, arrival));
    }

    @Then("the entry action codes are the canonical CREATE, AMEND and CANCEL")
    public void actionCodesAreCanonical() {
        assertEquals(List.of("CREATE", "AMEND", "CANCEL"), jdbc.queryForList(
                "SELECT action_code FROM mandate_request_entry WHERE arrival_id=? ORDER BY sequence",
                String.class, arrival));
    }

    @Then("every entry rests in spine state {string}")
    public void everyEntryInSpineState(String state) {
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=? AND spine_state != ?",
                Integer.class, arrival, state));
    }

    @Then("every entry carries a 35-character MndtReqId")
    public void everyEntryCarriesMndtReqId() {
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=? AND length(mndt_req_id) != 35",
                Integer.class, arrival));
    }

    @Then("the MndtReqIds are identical across the two runs")
    public void mndtReqIdsIdenticalAcrossRuns() {
        assertFalse(firstRunMndtReqIds.isEmpty(), "first run must have minted ids");
        assertEquals(firstRunMndtReqIds, mndtReqIds(),
                "deterministic minting: byte-verbatim replay re-mints identical ids (R-07)");
    }

    @Then("no spine entries are persisted for the arrival")
    public void noSpineEntries() {
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM mandate_request_entry WHERE arrival_id=?", Integer.class, arrival));
    }

    /** Short unique ref suffix: a full UUID would overflow the 35-char mandate_ref field. */
    String shortId() {
        return arrival.toString().substring(0, 8);
    }

    List<String> mndtReqIds() {
        return jdbc.queryForList(
                "SELECT mndt_req_id FROM mandate_request_entry WHERE arrival_id=? ORDER BY sequence",
                String.class, arrival);
    }

    JobParameters params(String attempt) {
        JobParametersBuilder builder = new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .addString("input.file", inputFile.toString(), false)
                .addString("original.name", MandateBookFixture.ORIGINAL_NAME, false);
        if (attempt != null) {
            builder.addString("attempt", attempt, true);
        }
        return builder.toJobParameters();
    }
}
