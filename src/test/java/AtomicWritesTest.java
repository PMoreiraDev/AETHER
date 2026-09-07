import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import util.AtomicWrites;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AtomicWrites}: atomic file writes must not leave a truncated
 * file even on overwrite, and the temp file must not linger after success.
 */
class AtomicWritesTest {

    @TempDir
    Path tempDir;

    @Test
    void write_createsFileWithExactContent() throws IOException {
        Path target = tempDir.resolve("note.md");
        AtomicWrites.write(target, "# Hello\nbody text");
        assertTrue(Files.exists(target));
        assertEquals("# Hello\nbody text", Files.readString(target));
    }

    @Test
    void write_overwriteReplacesContentAtomically() throws IOException {
        Path target = tempDir.resolve("event.md");
        AtomicWrites.write(target, "old content that is longer than the new");
        AtomicWrites.write(target, "new");
        assertEquals("new", Files.readString(target));
    }

    @Test
    void write_leavesNoTempFileBehind() throws IOException {
        Path target = tempDir.resolve("task.md");
        AtomicWrites.write(target, "content");
        long tmpFiles = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().endsWith(".aether-tmp"))
                .count();
        assertEquals(0, tmpFiles, "temp file should be removed after atomic move");
    }

    @Test
    void writeBytes_atomicBinaryWrite() throws IOException {
        Path target = tempDir.resolve("backup.db");
        byte[] data = {0x00, 0x01, 0x02, (byte) 0xFF};
        AtomicWrites.writeBytes(target, data);
        assertTrue(Files.exists(target));
        assertEquals(data.length, Files.size(target));
    }
}
