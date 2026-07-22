package za.co.fnb.dcre.mrr.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mrr.data.model.MandateRequestHeaderEntity;
import za.co.fnb.dcre.mrr.data.repo.MandateRequestHeaderRepo;
import za.co.fnb.dcre.platform.files.MandateLayouts;
import za.co.fnb.dcre.platform.files.R31Filename;
import za.co.fnb.dcre.platform.model.OpaqueRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business tier: mandate instruction book header parse + the file-fatal
 * structural tier (R-19 analog): empty book, header length, numeric header
 * fields, declared entry_count vs actual lines, R-31 filename-vs-header
 * cross-check. Persists via the data/repo tier only. Flow-agnostic: the man
 * route family is single-flow, so no flow launch parameter exists (plan T3).
 */
@Service
public class MandateBookReaderService {

    private final MandateRequestHeaderRepo repo;

    public MandateBookReaderService(MandateRequestHeaderRepo repo) {
        this.repo = repo;
    }

    /** @return the file-fatal reason, or empty when the header was accepted and persisted. */
    public Optional<String> ingestHeader(UUID arrivalId, Path input, String originalName) throws IOException {
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
            repo.upsert(MandateRequestHeaderEntity.of(arrivalId, msgId.rawBytes(), msgId.canonical(),
                    MandateLayouts.HEADER.slice(header, "created_ts"), declared, destination,
                    MandateLayouts.HEADER.slice(header, "business_date"),
                    tokens.map(R31Filename.Tokens::client).orElse(null), version));
            return Optional.empty();
        } catch (FileFatalException e) {
            return Optional.of(e.getMessage());
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
