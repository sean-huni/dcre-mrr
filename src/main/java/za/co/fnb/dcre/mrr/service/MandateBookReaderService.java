package za.co.fnb.dcre.mrr.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mrr.data.model.MandateRequestHeaderEntity;
import za.co.fnb.dcre.mrr.data.repo.MandateRequestEntryRepo;
import za.co.fnb.dcre.mrr.data.repo.MandateRequestHeaderRepo;
import za.co.fnb.dcre.mrr.domain.IntraFileDuplicates;
import za.co.fnb.dcre.mrr.domain.MndtReqIdMinter;
import za.co.fnb.dcre.platform.files.MandateLayouts;
import za.co.fnb.dcre.platform.files.R31Filename;
import za.co.fnb.dcre.platform.model.OpaqueRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business tier: mandate instruction book ingest. Parses + persists the header,
 * runs the whole-file structural tier (empty book, header length, numeric header
 * fields, declared entry_count vs actual lines, R-31 filename-vs-header), the
 * first-wins intra-file duplicate scan (B1a) and the cross-arrival MndtReqId
 * collision pre-flight (B1b). Persists via the data/repo tier only. Flow-agnostic
 * (the man route family is single-flow, no flow launch parameter, plan T3).
 *
 * <p>CONTENT-HASH-DEDUP (deferred, plan T3 / R-41 analog): a filename-independent
 * identical-bytes no-op keyed on an arrival-level content hash is an AGT-tier
 * concern (R-16 quarantine) and is intentionally NOT built here; MRR's belt is the
 * deterministic MndtReqId UNIQUE plus the collision pre-flight below. Note, not build.
 */
@Service
public class MandateBookReaderService {

    /** Ingest verdict: a file-fatal reason (business NACK) OR the later-duplicate sequences as a CSV. */
    public record IngestOutcome(Optional<String> fileFatalReason, String duplicateSequences) {

        static IngestOutcome fatal(String reason) {
            return new IngestOutcome(Optional.of(reason), "");
        }

        static IngestOutcome accepted(String duplicateSequences) {
            return new IngestOutcome(Optional.empty(), duplicateSequences);
        }
    }

    private final MandateRequestHeaderRepo headerRepo;
    private final MandateRequestEntryRepo entryRepo;

    public MandateBookReaderService(MandateRequestHeaderRepo headerRepo, MandateRequestEntryRepo entryRepo) {
        this.headerRepo = headerRepo;
        this.entryRepo = entryRepo;
    }

    public IngestOutcome ingest(UUID arrivalId, Path input, String originalName) throws IOException {
        try {
            // ISO_8859_1: byte-transparent (one byte = one char), same contract as
            // the partitioned range reader; strict UTF-8 would crash on legacy bytes
            List<String> lines = Files.readAllLines(input, StandardCharsets.ISO_8859_1);
            if (lines.isEmpty()) {
                throw new FileFatalException("empty file");
            }
            String header = lines.get(0);
            if (header.length() < MandateLayouts.HEADER.length()) {
                throw new FileFatalException("header shorter than attested content length "
                        + MandateLayouts.HEADER.length());
            }
            int version = numeric(header, "layout_version");
            int declared = numeric(header, "entry_count");
            int actual = lines.size() - 1;
            if (declared != actual) {
                throw new FileFatalException("header entry_count=" + declared + " but file has " + actual);
            }
            String destination = MandateLayouts.HEADER.slice(header, "destination_id").strip();
            Optional<R31Filename.Tokens> tokens = originalName != null
                    ? R31Filename.parse(originalName) : Optional.empty();
            if (tokens.isPresent() && !tokens.get().client().equals(destination)) {
                throw new FileFatalException("R-31 mismatch: filename client " + tokens.get().client()
                        + " != header destination " + destination);
            }
            OpaqueRef msgId = OpaqueRef.ofFixedWidth(header.substring(4, 26));
            List<IntraFileDuplicates.Row> rows = detailRows(lines);
            crossArrivalCollisionGuard(arrivalId, destination, msgId.canonical(), rows);
            repo(arrivalId, header, msgId, declared, destination, tokens, version);
            return IngestOutcome.accepted(IntraFileDuplicates.scan(rows).toCsv());
        } catch (FileFatalException e) {
            return IngestOutcome.fatal(e.getMessage());
        }
    }

    private void repo(UUID arrivalId, String header, OpaqueRef msgId, int declared, String destination,
                      Optional<R31Filename.Tokens> tokens, int version) {
        headerRepo.upsert(MandateRequestHeaderEntity.of(arrivalId, msgId.rawBytes(), msgId.canonical(),
                MandateLayouts.HEADER.slice(header, "created_ts"), declared, destination,
                MandateLayouts.HEADER.slice(header, "business_date"),
                tokens.map(R31Filename.Tokens::client).orElse(null), version));
    }

    /** Well-formed detail rows keyed for dedup; a malformed-LRECL line is the detail-step reader's structural verdict (TECH). */
    private static List<IntraFileDuplicates.Row> detailRows(List<String> lines) {
        List<IntraFileDuplicates.Row> rows = new ArrayList<>(lines.size());
        for (int seq = 1; seq < lines.size(); seq++) {
            String line = lines.get(seq);
            if (line.length() == MandateLayouts.DETAIL.length()) {
                rows.add(new IntraFileDuplicates.Row(seq,
                        MandateLayouts.DETAIL.slice(line, "mandate_ref").strip(),
                        MandateEntryWriter.canonicalAction(MandateLayouts.DETAIL.slice(line, "action_code"))));
            }
        }
        return rows;
    }

    /**
     * B1b: the deterministic mint of the first (always non-duplicate) row is checked
     * against other arrivals. A same-msg_id resubmit under a NEW arrival re-mints the
     * identical id, so a match is a whole-file business FILE_FATAL at ingest (NACK via
     * MIR, zero rows persisted) rather than a mid-chunk UNIQUE poison-pill; the DAO
     * belt covers the residual write-race. Replays are unaffected (same arrival excluded).
     */
    private void crossArrivalCollisionGuard(UUID arrivalId, String client, String msgId,
                                            List<IntraFileDuplicates.Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        IntraFileDuplicates.Row first = rows.get(0);
        String mintedId = MndtReqIdMinter.mint(client, first.mandateRef(), first.actionCode(), msgId);
        if (entryRepo.countByMndtReqIdUnderOtherArrival(mintedId, arrivalId) > 0) {
            throw new FileFatalException("MndtReqId collision: " + mintedId
                    + " already minted under another arrival (cross-arrival same-msg_id resubmit past AGT R-16)");
        }
    }

    /** Malformed numeric header fields are a whole-file structural verdict, never a crash. */
    private static int numeric(String header, String field) {
        String raw = MandateLayouts.HEADER.slice(header, field).strip();
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new FileFatalException("header " + field + " is not numeric: '" + raw + "'");
        }
    }
}
