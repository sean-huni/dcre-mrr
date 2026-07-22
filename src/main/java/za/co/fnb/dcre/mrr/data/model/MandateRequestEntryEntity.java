package za.co.fnb.dcre.mrr.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.math.BigDecimal;
import java.util.UUID;

@Table("mandate_request_entry")
public class MandateRequestEntryEntity extends BaseEntity {

    private UUID arrivalId;
    private Integer sequence;
    private String recordType;
    private String actionCode;
    private String mandateRef;
    private String contractRef;
    private String creditorAccount;
    private String debtorAccount;
    private String debtorBranch;
    private String debtorName;
    private String currency;
    private String maxCollectionAmountRaw;
    private BigDecimal maxCollectionAmount;
    private String frequency;
    private String collectionDay;
    private String startDate;
    private String expiryDate;
    private String mndtReqId;
    private String spineState;

    public static MandateRequestEntryEntity of(UUID arrivalId, int sequence, String recordType,
                                               String actionCode, String mandateRef, String contractRef,
                                               String creditorAccount, String debtorAccount,
                                               String debtorBranch, String debtorName, String currency,
                                               String maxCollectionAmountRaw, BigDecimal maxCollectionAmount,
                                               String frequency, String collectionDay, String startDate,
                                               String expiryDate, String mndtReqId) {
        MandateRequestEntryEntity e = new MandateRequestEntryEntity();
        e.arrivalId = arrivalId;
        e.sequence = sequence;
        e.recordType = recordType;
        e.actionCode = actionCode;
        e.mandateRef = mandateRef;
        e.contractRef = contractRef;
        e.creditorAccount = creditorAccount;
        e.debtorAccount = debtorAccount;
        e.debtorBranch = debtorBranch;
        e.debtorName = debtorName;
        e.currency = currency;
        e.maxCollectionAmountRaw = maxCollectionAmountRaw;
        e.maxCollectionAmount = maxCollectionAmount;
        e.frequency = frequency;
        e.collectionDay = collectionDay;
        e.startDate = startDate;
        e.expiryDate = expiryDate;
        e.mndtReqId = mndtReqId;
        return e;
    }

    public UUID getArrivalId() { return arrivalId; }
    public Integer getSequence() { return sequence; }
    public String getRecordType() { return recordType; }
    public String getActionCode() { return actionCode; }
    public String getMandateRef() { return mandateRef; }
    public String getContractRef() { return contractRef; }
    public String getCreditorAccount() { return creditorAccount; }
    public String getDebtorAccount() { return debtorAccount; }
    public String getDebtorBranch() { return debtorBranch; }
    public String getDebtorName() { return debtorName; }
    public String getCurrency() { return currency; }
    public String getMaxCollectionAmountRaw() { return maxCollectionAmountRaw; }
    public BigDecimal getMaxCollectionAmount() { return maxCollectionAmount; }
    public String getFrequency() { return frequency; }
    public String getCollectionDay() { return collectionDay; }
    public String getStartDate() { return startDate; }
    public String getExpiryDate() { return expiryDate; }
    public String getMndtReqId() { return mndtReqId; }
    public String getSpineState() { return spineState; }
}
