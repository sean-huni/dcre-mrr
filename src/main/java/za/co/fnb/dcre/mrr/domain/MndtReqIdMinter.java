package za.co.fnb.dcre.mrr.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * R-07 idempotent minting: the MndtReqId is a DETERMINISTIC digest over the
 * FULL business identity tuple (client, mandate_ref, action_code, msg_id),
 * never a random draw, so a byte-verbatim replay re-mints the identical id
 * and the spine UNIQUE constraint arbitrates duplicates instead of minting
 * drift. Every component is mandatory (house idempotency-key rule: no
 * nullable dimensions in an identity tuple). Shape: "MRQ" + 32 hex chars of
 * SHA-256 = 35 chars, the ISO 20022 MndtReqId maximum.
 */
public final class MndtReqIdMinter {

    static final String PREFIX = "MRQ";
    static final int LENGTH = 35;

    private MndtReqIdMinter() {
    }

    public static String mint(String client, String mandateRef, String actionCode, String msgId) {
        String tuple = String.join("|",
                required("client", client),
                required("mandateRef", mandateRef),
                required("actionCode", actionCode),
                required("msgId", msgId));
        String hex = HexFormat.of().formatHex(
                sha256().digest(tuple.getBytes(StandardCharsets.UTF_8)));
        return PREFIX + hex.substring(0, LENGTH - PREFIX.length());
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "MndtReqId identity component '" + name + "' is blank: full-identity tuple required (R-07)");
        }
        return value.strip();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
