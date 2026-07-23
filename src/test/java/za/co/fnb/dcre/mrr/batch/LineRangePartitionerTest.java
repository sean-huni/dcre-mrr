package za.co.fnb.dcre.mrr.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import za.co.fnb.dcre.mrr.domain.FileFatalException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Detail records split into contiguous [fromRecord, toRecord) byte ranges; mandate DETAIL LRECL 285. */
class LineRangePartitionerTest {

    private static long from(ExecutionContext c) { return c.getLong("fromRecord"); }
    private static long to(ExecutionContext c) { return c.getLong("toRecord"); }

    private static final String HEADER_PADDED = "H".repeat(285);
    private static final String HEADER_UNPADDED = "H".repeat(109);
    private static final String DETAIL = "D".repeat(285);

    private static Path file(Path dir, String content) throws IOException {
        Path f = dir.resolve("fixture.txt");
        Files.writeString(f, content, StandardCharsets.ISO_8859_1);
        return f;
    }

    @Test
    void missingTrailingNewlineKeepsLastRecord(@TempDir Path dir) throws IOException {
        Path f = file(dir, HEADER_PADDED + "\n" + DETAIL + "\n" + DETAIL + "\n" + DETAIL);
        Map<String, ExecutionContext> parts = new LineRangePartitioner(f.toString()).partition(1);
        assertEquals(1, parts.size());
        assertEquals(0, from(parts.get("partition0")));
        assertEquals(3, to(parts.get("partition0")));
    }

    @Test
    void unpaddedHeaderDerivesStrideFromDetailLine(@TempDir Path dir) throws IOException {
        // header 109 bytes (attested content length), details 285: base offset
        // and stride must come from lines 1 and 2 respectively
        Path f = file(dir, HEADER_UNPADDED + "\n" + DETAIL + "\n" + DETAIL + "\n");
        Map<String, ExecutionContext> parts = new LineRangePartitioner(f.toString()).partition(1);
        assertEquals(1, parts.size());
        ExecutionContext c = parts.get("partition0");
        assertEquals(0, from(c));
        assertEquals(2, to(c));
        assertEquals(285, c.getInt("lrecl"));
        assertEquals(110, c.getLong("baseOffset"));
    }

    @Test
    void nonUtf8ByteDoesNotBreakLengthProbing(@TempDir Path dir) throws IOException {
        // 0xE9 in debtor_name: line lengths are byte counts, never charset decodes
        String detail = "D".repeat(150) + "\u00E9" + "D".repeat(134);
        Path f = file(dir, HEADER_PADDED + "\n" + detail + "\n");
        Map<String, ExecutionContext> parts = new LineRangePartitioner(f.toString()).partition(1);
        assertEquals(1, parts.size());
        assertEquals(1, to(parts.get("partition0")));
        assertEquals(285, parts.get("partition0").getInt("lrecl"));
    }

    @Test
    void raggedFileLengthFailsClosed(@TempDir Path dir) throws IOException {
        Path f = file(dir, HEADER_PADDED + "\n" + DETAIL + "\n" + "X".repeat(100));
        LineRangePartitioner partitioner = new LineRangePartitioner(f.toString());
        assertThrows(FileFatalException.class, () -> partitioner.partition(1));
    }

    @Test
    void collectionsLreclFailsClosed(@TempDir Path dir) throws IOException {
        // 169 is the COLLECTIONS detail LRECL: a copybook dropped on the man
        // route must fail closed, not half-parse
        Path f = file(dir, HEADER_PADDED + "\n" + "D".repeat(169) + "\n");
        LineRangePartitioner partitioner = new LineRangePartitioner(f.toString());
        assertThrows(FileFatalException.class, () -> partitioner.partition(1));
    }

    @Test
    void splitsEvenlyWithRemainderOnLastPartition() {
        Map<String, ExecutionContext> parts = LineRangePartitioner.split(10, 3);
        assertEquals(3, parts.size());
        assertEquals(0, from(parts.get("partition0")));
        assertEquals(4, to(parts.get("partition0")));
        assertEquals(4, from(parts.get("partition1")));
        assertEquals(8, to(parts.get("partition1")));
        assertEquals(8, from(parts.get("partition2")));
        assertEquals(10, to(parts.get("partition2")));
    }

    @Test
    void fewerRecordsThanPartitionsCollapses() {
        assertEquals(2, LineRangePartitioner.split(2, 5).size());
    }

    @Test
    void zeroRecordsYieldsNoPartitions() {
        assertEquals(0, LineRangePartitioner.split(0, 5).size());
    }
}
