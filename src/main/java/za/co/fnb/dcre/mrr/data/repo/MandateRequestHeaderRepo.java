package za.co.fnb.dcre.mrr.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mrr.data.model.MandateRequestHeaderEntity;

import java.util.Optional;
import java.util.UUID;

/** Guarded native UPSERT keyed by the R-05 identity (arrival_id): restarts rewrite, never duplicate. */
public interface MandateRequestHeaderRepo extends CrudRepository<MandateRequestHeaderEntity, UUID> {

    @Modifying
    @Query("""
            INSERT INTO mandate_request_header (arrival_id, msg_id_raw, msg_id, created_ts, entry_count, destination_id, business_date, client_token, layout_version)
            VALUES (:#{#e.arrivalId}, :#{#e.msgIdRaw}, :#{#e.msgId}, :#{#e.createdTs}, :#{#e.entryCount}, :#{#e.destinationId}, :#{#e.businessDate}, :#{#e.clientToken}, :#{#e.layoutVersion})
            ON CONFLICT (arrival_id) DO UPDATE SET msg_id_raw = EXCLUDED.msg_id_raw, msg_id = EXCLUDED.msg_id, created_ts = EXCLUDED.created_ts, entry_count = EXCLUDED.entry_count, destination_id = EXCLUDED.destination_id, business_date = EXCLUDED.business_date, client_token = EXCLUDED.client_token, layout_version = EXCLUDED.layout_version""")
    void upsert(@Param("e") MandateRequestHeaderEntity e);

    Optional<MandateRequestHeaderEntity> findByArrivalId(UUID arrivalId);
}
