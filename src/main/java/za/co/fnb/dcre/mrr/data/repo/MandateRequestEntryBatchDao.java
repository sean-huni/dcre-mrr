package za.co.fnb.dcre.mrr.data.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.mrr.data.model.MandateRequestEntryEntity;
import za.co.fnb.dcre.mrr.domain.FileFatalException;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * Sole owner of the mandate_request_entry write SQL: the guarded UPSERT keyed
 * (arrival_id, sequence) (R-05), driven through JdbcTemplate.batchUpdate in
 * 500-row batches. spine_state is deliberately ABSENT from both the insert
 * column list (DB default RECEIVED) and the DO UPDATE set: MRV/MAF/MIT own
 * that column family (ruling note 2), so a replayed ingest never clobbers a
 * downstream stage's advancement. dup_in_file/mndt_req_id are set per row: a
 * first-wins intra-file duplicate lands NULL id + dup_in_file=true (B1a).
 */
@Component
public class MandateRequestEntryBatchDao {

    static final int BATCH_SIZE = 500;

    private static final String UPSERT_SQL = """
            INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code, mandate_ref, contract_ref, creditor_account, debtor_account, debtor_branch, debtor_name, currency, max_collection_amount_raw, max_collection_amount, frequency, collection_day, start_date, expiry_date, mndt_req_id, dup_in_file)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (arrival_id, sequence) DO UPDATE SET record_type = EXCLUDED.record_type, action_code = EXCLUDED.action_code, mandate_ref = EXCLUDED.mandate_ref, contract_ref = EXCLUDED.contract_ref, creditor_account = EXCLUDED.creditor_account, debtor_account = EXCLUDED.debtor_account, debtor_branch = EXCLUDED.debtor_branch, debtor_name = EXCLUDED.debtor_name, currency = EXCLUDED.currency, max_collection_amount_raw = EXCLUDED.max_collection_amount_raw, max_collection_amount = EXCLUDED.max_collection_amount, frequency = EXCLUDED.frequency, collection_day = EXCLUDED.collection_day, start_date = EXCLUDED.start_date, expiry_date = EXCLUDED.expiry_date, mndt_req_id = EXCLUDED.mndt_req_id, dup_in_file = EXCLUDED.dup_in_file""";

    private final JdbcTemplate jdbc;

    public MandateRequestEntryBatchDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void batchUpsert(List<MandateRequestEntryEntity> entities) {
        if (entities.isEmpty()) {
            return;
        }
        try {
            jdbc.batchUpdate(UPSERT_SQL, entities, BATCH_SIZE, MandateRequestEntryBatchDao::bind);
        } catch (DuplicateKeyException e) {
            throw collisionAsFileFatal(e);
        }
    }

    /**
     * B1b defense-in-depth belt: the ONLY UNIQUE this INSERT can violate at
     * runtime is uq_man_req_entry_mndt_req_id (the (arrival_id, sequence) UNIQUE
     * is absorbed by ON CONFLICT; the PK is a DB-generated UUID). A cross-arrival
     * same-msg_id resubmit that slips past AGT R-16 AND the ingest collision
     * pre-flight (a true write-race) becomes a diagnosed FileFatalException, never
     * a raw CockroachDB crash loop. In the partitioned detail step this remains a
     * TECH-tier job death (the business FILE_FATAL verdict is the ingest pre-flight
     * in MandateBookReaderService); an unrecognised duplicate is rethrown untouched.
     */
    private static RuntimeException collisionAsFileFatal(DuplicateKeyException e) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        if (cause.contains("uq_man_req_entry_mndt_req_id") || cause.contains("mndt_req_id")) {
            return new FileFatalException("MndtReqId collision: a minted MndtReqId already exists under "
                    + "another arrival (cross-arrival same-msg_id resubmit past AGT R-16): " + cause);
        }
        return e;
    }

    private static void bind(PreparedStatement ps, MandateRequestEntryEntity e) throws SQLException {
        ps.setObject(1, e.getArrivalId());
        ps.setInt(2, e.getSequence());
        ps.setString(3, e.getRecordType());
        ps.setString(4, e.getActionCode());
        ps.setString(5, e.getMandateRef());
        ps.setString(6, e.getContractRef());
        ps.setString(7, e.getCreditorAccount());
        ps.setString(8, e.getDebtorAccount());
        ps.setString(9, e.getDebtorBranch());
        ps.setString(10, e.getDebtorName());
        ps.setString(11, e.getCurrency());
        ps.setString(12, e.getMaxCollectionAmountRaw());
        ps.setBigDecimal(13, e.getMaxCollectionAmount());
        ps.setString(14, e.getFrequency());
        ps.setString(15, e.getCollectionDay());
        ps.setString(16, e.getStartDate());
        ps.setString(17, e.getExpiryDate());
        if (e.getMndtReqId() == null) {
            ps.setNull(18, Types.VARCHAR); // first-wins intra-file duplicate (B1a): NULL exempt from the UNIQUE
        } else {
            ps.setString(18, e.getMndtReqId());
        }
        ps.setBoolean(19, e.getDupInFile());
    }
}
