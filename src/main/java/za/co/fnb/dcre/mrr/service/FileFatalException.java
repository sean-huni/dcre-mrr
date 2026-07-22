package za.co.fnb.dcre.mrr.service;

/** Structural failure of the whole book: a BUSINESS verdict (NACK via MIR), never a process death. */
public class FileFatalException extends RuntimeException {

    public FileFatalException(String message) {
        super(message);
    }
}
