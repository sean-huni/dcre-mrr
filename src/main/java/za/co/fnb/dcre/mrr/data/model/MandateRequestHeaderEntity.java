package za.co.fnb.dcre.mrr.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.util.UUID;

@Table("mandate_request_header")
public class MandateRequestHeaderEntity extends BaseEntity {

    private UUID arrivalId;
    private String msgIdRaw;
    private String msgId;
    private String createdTs;
    private Integer entryCount;
    private String destinationId;
    private String businessDate;
    private String clientToken;
    private Integer layoutVersion;

    public static MandateRequestHeaderEntity of(UUID arrivalId, String msgIdRaw, String msgId,
                                                String createdTs, int entryCount, String destinationId,
                                                String businessDate, String clientToken, int layoutVersion) {
        MandateRequestHeaderEntity e = new MandateRequestHeaderEntity();
        e.arrivalId = arrivalId;
        e.msgIdRaw = msgIdRaw;
        e.msgId = msgId;
        e.createdTs = createdTs;
        e.entryCount = entryCount;
        e.destinationId = destinationId;
        e.businessDate = businessDate;
        e.clientToken = clientToken;
        e.layoutVersion = layoutVersion;
        return e;
    }

    public UUID getArrivalId() { return arrivalId; }
    public String getMsgIdRaw() { return msgIdRaw; }
    public String getMsgId() { return msgId; }
    public String getCreatedTs() { return createdTs; }
    public Integer getEntryCount() { return entryCount; }
    public String getDestinationId() { return destinationId; }
    public String getBusinessDate() { return businessDate; }
    public String getClientToken() { return clientToken; }
    public Integer getLayoutVersion() { return layoutVersion; }
}
