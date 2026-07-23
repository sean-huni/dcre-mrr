package za.co.fnb.dcre.mrr.service;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.UUID;

/** Thin entry adapter (3-tier, configuration.md point 21). */
@Component
public class BookHeaderTasklet implements Tasklet {

    public static final String EXIT_FILE_FATAL = "FILE_FATAL";

    private final MandateBookReaderService service;

    public BookHeaderTasklet(MandateBookReaderService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        var params = chunkContext.getStepContext().getJobParameters();
        ExecutionContext jobContext = chunkContext.getStepContext().getStepExecution()
                .getJobExecution().getExecutionContext();
        MandateBookReaderService.IngestOutcome outcome = service.ingest(
                UUID.fromString((String) params.get("arrival.id")),
                Path.of((String) params.get("input.file")),
                (String) params.get("original.name"));
        if (outcome.fileFatalReason().isPresent()) {
            jobContext.putString("fileFatalReason", outcome.fileFatalReason().get());
            contribution.setExitStatus(new ExitStatus(EXIT_FILE_FATAL, outcome.fileFatalReason().get()));
        } else {
            // ferried to the partitioned entry writer via jobExecutionContext (B1a first-wins dedup)
            jobContext.putString("dupSequences", outcome.duplicateSequences());
        }
        return RepeatStatus.FINISHED;
    }
}
