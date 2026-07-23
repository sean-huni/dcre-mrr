package za.co.fnb.dcre.mrr.service;

/**
 * A whole-book fatal condition. The TIER decides whether it is a business verdict
 * or a technical death (m1, stated honestly):
 * <ul>
 *   <li>Thrown from the INGEST tier (header structural checks + the cross-arrival
 *       MndtReqId collision pre-flight in {@link MandateBookReaderService}): caught
 *       there and routed to the FILE_FATAL exit, so the job COMPLETES with a BUSINESS
 *       verdict (NACK via MIR later), zero rows persisted.</li>
 *   <li>Thrown from the partitioned detail step (the range reader's LRECL/overrun
 *       structural checks, or the DAO's collision belt): NOT caught into a business
 *       verdict; it fails the worker step, so it is a TECH-tier job death. The
 *       non-zero exit code and the K8s Failed condition are the witnesses (R-33),
 *       never a NACK. The DAO belt only ensures this is a DIAGNOSED death, not a raw
 *       CockroachDB crash loop.</li>
 * </ul>
 */
public class FileFatalException extends RuntimeException {

    public FileFatalException(String message) {
        super(message);
    }
}
