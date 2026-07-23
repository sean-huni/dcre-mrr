package za.co.fnb.dcre.mrr.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R-07: deterministic full-identity minting, 35-char ISO 20022 shape. */
class MndtReqIdMinterTest {

    static final String CLIENT = "FNBCC01";
    static final String REF = "MREF-CRE-001";
    static final String ACTION = "CREATE";
    static final String MSG = "FNB1MB20260722083000 1";

    @Test
    void sameTupleMintsSameId() {
        assertEquals(MndtReqIdMinter.mint(CLIENT, REF, ACTION, MSG),
                MndtReqIdMinter.mint(CLIENT, REF, ACTION, MSG),
                "replay must re-mint the identical id");
    }

    @Test
    void shapeIsPrefixedThirtyFiveChars() {
        String id = MndtReqIdMinter.mint(CLIENT, REF, ACTION, MSG);
        assertEquals(35, id.length());
        assertTrue(id.startsWith("MRQ"), "self-describing prefix: " + id);
    }

    @Test
    void everyTupleDimensionChangesTheId() {
        String base = MndtReqIdMinter.mint(CLIENT, REF, ACTION, MSG);
        assertNotEquals(base, MndtReqIdMinter.mint("FNBRF01", REF, ACTION, MSG), "client dimension");
        assertNotEquals(base, MndtReqIdMinter.mint(CLIENT, "MREF-CRE-002", ACTION, MSG), "mandate_ref dimension");
        assertNotEquals(base, MndtReqIdMinter.mint(CLIENT, REF, "CANCEL", MSG), "action_code dimension");
        assertNotEquals(base, MndtReqIdMinter.mint(CLIENT, REF, ACTION, MSG + "X"), "msg_id dimension");
    }

    @Test
    void delimiterAmbiguityDoesNotCollide() {
        // m6: length-prefixed component encoding, never a raw delimiter join:
        // ("A", "B|C") and ("A|B", "C") must digest differently.
        assertNotEquals(MndtReqIdMinter.mint("A", "B|C", ACTION, MSG),
                MndtReqIdMinter.mint("A|B", "C", ACTION, MSG),
                "component boundaries must be part of the digest input");
    }

    @Test
    void blankIdentityComponentFailsClosed() {
        // house idempotency-key rule: no nullable dimensions, no null_null keys
        assertThrows(IllegalArgumentException.class, () -> MndtReqIdMinter.mint(null, REF, ACTION, MSG));
        assertThrows(IllegalArgumentException.class, () -> MndtReqIdMinter.mint(CLIENT, " ", ACTION, MSG));
        assertThrows(IllegalArgumentException.class, () -> MndtReqIdMinter.mint(CLIENT, REF, "", MSG));
        assertThrows(IllegalArgumentException.class, () -> MndtReqIdMinter.mint(CLIENT, REF, ACTION, null));
    }
}
