package za.co.fnb.dcre.mrr.service;

import za.co.fnb.dcre.mrr.data.model.MandateRequestEntryEntity;
import za.co.fnb.dcre.mrr.data.model.MandateRequestHeaderEntity;
import za.co.fnb.dcre.mrr.data.repo.MandateRequestEntryBatchDao;
import za.co.fnb.dcre.mrr.data.repo.MandateRequestHeaderRepo;
import za.co.fnb.dcre.mrr.domain.MndtReqIdMinter;
import za.co.fnb.dcre.platform.files.FixedWidthLayout;
import za.co.fnb.dcre.platform.files.MandateLayouts;
import za.co.fnb.dcre.platform.model.MoneyText;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists instruction records via the guarded UPSERT keyed
 * (arrival_id, sequence): a restarted chunk rewrites identical rows, never
 * duplicates (R-05). Sequence derives from the record's position in the file
 * (recordIndex + 1), never from shared counters, so partitioned ingest is
 * deterministic. Action codes canonicalize CRE|AMD|CAN to CREATE|AMEND|CANCEL
 * (unknown tokens pass through raw for MRV's structural verdict). MndtReqId
 * is minted deterministically from (client, mandate_ref, action_code, msg_id)
 * and persisted here, write-ahead of every downstream side effect (R-07).
 */
public class MandateEntryWriter {

    /** A detail line plus its 0-based position among the file's detail records. */
    public record NumberedLine(long recordIndex, String line) {
    }

    private final MandateRequestEntryBatchDao dao;
    private final MandateRequestHeaderRepo headerRepo;
    private final UUID arrivalId;
    private final int amountScale;

    private MandateRequestHeaderEntity header;

    public MandateEntryWriter(MandateRequestEntryBatchDao dao, MandateRequestHeaderRepo headerRepo,
                              UUID arrivalId, int amountScale) {
        this.dao = dao;
        this.headerRepo = headerRepo;
        this.arrivalId = arrivalId;
        this.amountScale = amountScale;
    }

    public void writeDetails(List<? extends NumberedLine> lines) {
        List<MandateRequestEntryEntity> entities = new ArrayList<>(lines.size());
        for (NumberedLine numbered : lines) {
            entities.add(toEntity(numbered.line(), (int) numbered.recordIndex() + 1));
        }
        dao.batchUpsert(entities);
    }

    MandateRequestEntryEntity toEntity(String line, int seq) {
        FixedWidthLayout layout = layoutFor(line);
        String actionCode = canonicalAction(layout.slice(line, "action_code"));
        String mandateRef = layout.slice(line, "mandate_ref").strip();
        String amountRaw = layout.slice(line, "max_collection_amount");
        String expiry = layout.slice(line, "expiry_date").strip();
        return MandateRequestEntryEntity.of(arrivalId, seq,
                layout.slice(line, "record_type"),
                actionCode,
                mandateRef,
                layout.slice(line, "contract_ref").strip(),
                layout.slice(line, "creditor_account").strip(),
                layout.slice(line, "debtor_account").strip(),
                layout.slice(line, "debtor_branch").strip(),
                layout.slice(line, "debtor_name").strip(),
                layout.slice(line, "currency"),
                amountRaw,
                MoneyText.parse(amountRaw, amountScale),
                layout.slice(line, "frequency").strip(),
                layout.slice(line, "collection_day"),
                layout.slice(line, "start_date"),
                expiry.isEmpty() ? null : expiry,
                MndtReqIdMinter.mint(headerIdentity().getDestinationId(), mandateRef,
                        actionCode, headerIdentity().getMsgId()));
    }

    /** CRE|AMD|CAN canonicalize; anything else passes through raw for MRV (structural tier owner). */
    static String canonicalAction(String raw) {
        return switch (raw.strip()) {
            case "CRE" -> "CREATE";
            case "AMD" -> "AMEND";
            case "CAN" -> "CANCEL";
            default -> raw.strip();
        };
    }

    /**
     * Lazy header identity for MndtReqId minting: headerStep persisted the row
     * write-ahead of this partitioned step, so absence is a hard wiring fault.
     */
    private MandateRequestHeaderEntity headerIdentity() {
        if (header == null) {
            header = headerRepo.findByArrivalId(arrivalId)
                    .orElseThrow(() -> new IllegalStateException(
                            "no mandate_request_header row for arrival " + arrivalId
                                    + ": headerStep must persist before detail ingest"));
        }
        return header;
    }

    FixedWidthLayout layoutFor(String line) {
        if (line.length() == MandateLayouts.DETAIL.length()) {
            return MandateLayouts.DETAIL;
        }
        throw new FileFatalException("detail LRECL " + line.length() + " matches no mandate layout");
    }
}
