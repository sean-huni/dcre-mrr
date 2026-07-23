package za.co.fnb.dcre.mrr.data.repo;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mrr.data.model.MandateRequestEntryEntity;

import java.util.UUID;

/** Read side of mandate_request_entry; ALL writes go through MandateRequestEntryBatchDao (single SQL owner). */
public interface MandateRequestEntryRepo extends CrudRepository<MandateRequestEntryEntity, UUID> {

    long countByArrivalId(UUID arrivalId);

    /**
     * B1b ingest collision pre-flight: rows carrying this deterministically-minted
     * MndtReqId under a DIFFERENT arrival. A cross-arrival same-msg_id resubmit
     * re-mints the identical id, so a non-zero count is a whole-file business
     * FILE_FATAL (the current arrival is excluded so byte-verbatim replays are
     * unaffected). Executed by the Order(8) collision test (SQL verified there).
     */
    @Query("SELECT count(*) FROM mandate_request_entry WHERE mndt_req_id = :mndtReqId AND arrival_id <> :arrivalId")
    long countByMndtReqIdUnderOtherArrival(@Param("mndtReqId") String mndtReqId, @Param("arrivalId") UUID arrivalId);
}
