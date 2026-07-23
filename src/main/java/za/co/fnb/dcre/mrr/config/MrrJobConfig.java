package za.co.fnb.dcre.mrr.config;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.mrr.batch.FixedRecordRangeReader;
import za.co.fnb.dcre.mrr.batch.LineRangePartitioner;
import za.co.fnb.dcre.mrr.data.repo.MandateRequestEntryBatchDao;
import za.co.fnb.dcre.mrr.data.repo.MandateRequestHeaderRepo;
import za.co.fnb.dcre.mrr.domain.IntraFileDuplicates;
import za.co.fnb.dcre.mrr.service.BookHeaderTasklet;
import za.co.fnb.dcre.mrr.service.MandateEntryWriter;
import za.co.fnb.dcre.platform.batch.CrdbRetryExceptionHandler;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;
import za.co.fnb.dcre.platform.batch.PartitionSizer;

import java.util.UUID;

/**
 * MRR job (CRR skeleton clone): headerStep (tasklet: parse + persist
 * mandate_request_header, structural checks, FILE_FATAL routing) then
 * detailStep, a partitioned manager/worker pair: byte-offset record ranges
 * fan out onto virtual threads, each worker a chunked range read -> batched
 * mandate_request_entry upsert, restartable per R-05. Identifying
 * JobParameter: arrival.id (R-16); flow-agnostic (no flow launch arg).
 */
@Configuration
public class MrrJobConfig {

    /**
     * CRDB 40001 retry for the tasklet WRITE step only (headerStep). NOT
     * registered on the chunk-oriented detailWorkerStep: a swallowed
     * commit-time abort there would silently drop the in-flight chunk
     * (verified empirically in CRR, 2026-07-14); a worker commit abort stays
     * step-FAILED -> relaunch, which resumes idempotently via read.count +
     * the guarded upsert keyed (arrival_id, sequence) (R-05).
     */
    private final CrdbRetryExceptionHandler crdbRetry = new CrdbRetryExceptionHandler("MRR");

    @Bean
    public Step headerStep(JobRepository repo, PlatformTransactionManager tx, BookHeaderTasklet headerTasklet) {
        return new StepBuilder("headerStep", repo)
                .tasklet(headerTasklet, tx)
                .exceptionHandler(crdbRetry)
                .build();
    }

    /**
     * Business-verdict seam (SYNTHETIC-CONTRACT, R-35) via the shared
     * OutcomeSeamListener (SCRUM-58): ACCEPTED on a clean run, FILE_FATAL on
     * a structural verdict. Technical failure writes nothing: the exit code
     * and the K8s Failed condition are the witnesses (R-33 arbiter clause).
     */
    @Bean
    public OutcomeSeamListener seamListener(@Value("${dcre.exchange-root}") String exchangeRoot) {
        return new OutcomeSeamListener("mrr", exchangeRoot,
                execution -> execution.getExecutionContext().containsKey("fileFatalReason")
                        ? "BUSINESS_FILE_FATAL" : "BUSINESS_ACCEPTED");
    }

    @Bean
    public Job mrrJob(JobRepository repo, Step headerStep, Step detailStep, OutcomeSeamListener listener,
                      HeartbeatWriter heartbeatWriter) {
        return new JobBuilder("mrrJob", repo)
                .listener(listener)
                .listener(heartbeatWriter)
                .start(headerStep)
                    .on(BookHeaderTasklet.EXIT_FILE_FATAL).end() // business verdict, job COMPLETED
                .from(headerStep).on("*").to(detailStep)
                .end()
                .build();
    }

    @Bean
    public Step detailStep(JobRepository repo, Step detailWorkerStep,
                           LineRangePartitioner partitioner,
                           @Value("${dcre.mrr.max-partitions:5}") int maxPartitions) {
        return new StepBuilder("detailStep", repo)
                .partitioner("detailWorkerStep", partitioner)
                .step(detailWorkerStep)
                .gridSize(PartitionSizer.partitions(maxPartitions))
                .taskExecutor(new VirtualThreadTaskExecutor("mrr-part-"))
                .build();
    }

    @Bean
    public Step detailWorkerStep(JobRepository repo, PlatformTransactionManager tx,
                                 FixedRecordRangeReader rangeReader, MandateEntryWriter writer) {
        return new StepBuilder("detailWorkerStep", repo)
                .<MandateEntryWriter.NumberedLine, MandateEntryWriter.NumberedLine>chunk(100, tx)
                .reader(rangeReader)
                .writer(chunk -> writer.writeDetails(chunk.getItems()))
                .build();
    }

    /**
     * The first-wins duplicate set (B1a) is scanned single-threaded at ingest and
     * ferried here through the job execution context as a CSV (same channel CTV uses
     * for asOfTimestamp): every partition worker sees the whole-file view, so a later
     * duplicate lands NULL id + dup_in_file=true regardless of which partition holds it.
     */
    @Bean
    @StepScope
    public MandateEntryWriter entryWriter(MandateRequestEntryBatchDao dao,
                                          MandateRequestHeaderRepo headerRepo,
                                          @Value("#{jobParameters['arrival.id']}") String arrivalId,
                                          @Value("#{jobExecutionContext['dupSequences']}") String dupSequences,
                                          @Value("${dcre.amount-scale}") int amountScale) {
        return new MandateEntryWriter(dao, headerRepo, UUID.fromString(arrivalId), amountScale,
                IntraFileDuplicates.parse(dupSequences));
    }
}
