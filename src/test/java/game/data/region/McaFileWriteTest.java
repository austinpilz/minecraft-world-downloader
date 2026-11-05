package game.data.region;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class McaFileWriteTest {
    @TempDir
    Path dir;

    @Test
    void writesNewFileWithoutLeavingTemp() throws IOException {
        Path target = dir.resolve("r.0.0.mca");

        McaFile.writeAtomically(target, bytes("new"));

        assertThat(target).hasBinaryContent(bytes("new"));
        assertThat(dir.resolve("r.0.0.mca.tmp")).doesNotExist();
    }

    @Test
    void replacesExistingFile() throws IOException {
        Path target = dir.resolve("r.1.-2.mca");
        Files.write(target, bytes("old contents that are longer"));

        McaFile.writeAtomically(target, bytes("new"));

        assertThat(target).hasBinaryContent(bytes("new"));
        try (var files = Files.list(dir)) {
            assertThat(files).containsExactly(target);
        }
    }

    @Test
    void staleTempFileFromCrashIsReplaced() throws IOException {
        Path target = dir.resolve("r.0.0.mca");
        Files.write(dir.resolve("r.0.0.mca.tmp"), bytes("half written"));

        McaFile.writeAtomically(target, bytes("new"));

        assertThat(target).hasBinaryContent(bytes("new"));
        assertThat(dir.resolve("r.0.0.mca.tmp")).doesNotExist();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
