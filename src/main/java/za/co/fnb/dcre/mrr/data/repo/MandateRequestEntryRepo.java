package za.co.fnb.dcre.mrr.data.repo;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mrr.data.model.MandateRequestEntryEntity;

import java.util.Collection;
import java.util.UUID;

/** Read side of mandate_request_entry; ALL writes go through MandateRequestEntryBatchDao (single SQL owner). */
public interface MandateRequestEntryRepo extends CrudRepository<MandateRequestEntryEntity, UUID> {

    long countByArrivalId(UUID arrivalId);

    /**
     * B1b ingest collision pre-flight: rows carrying ANY of these deterministically
     * minted MndtReqIds under a DIFFERENT arrival, in one IN-list scan. A
     * cross-arrival same-msg_id resubmit re-mints identical ids even when only a
     * LATER/reordered row overlaps, so ALL first-occurrence ids of the book are
     * checked (not just the first row); a non-zero count is a whole-file business
     * FILE_FATAL. The current arrival is excluded so byte-verbatim replays are
     * unaffected. Executed by the Order(8)/Order(9) collision tests (SQL verified there).
     */
    @Query("SELECT count(*) FROM mandate_request_entry WHERE mndt_req_id IN (:mndtReqIds) AND arrival_id <> :arrivalId")
    long countByMndtReqIdInUnderOtherArrival(@Param("mndtReqIds") Collection<String> mndtReqIds,
                                             @Param("arrivalId") UUID arrivalId);
}
