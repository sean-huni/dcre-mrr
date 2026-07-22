package za.co.fnb.dcre.mrr.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.mrr.data.model.MandateRequestEntryEntity;

import java.util.UUID;

/** Read side of mandate_request_entry; ALL writes go through MandateRequestEntryBatchDao (single SQL owner). */
public interface MandateRequestEntryRepo extends CrudRepository<MandateRequestEntryEntity, UUID> {

    long countByArrivalId(UUID arrivalId);
}
