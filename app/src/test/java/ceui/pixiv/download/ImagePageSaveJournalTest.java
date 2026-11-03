package ceui.pixiv.download;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class ImagePageSaveJournalTest {
    private static ImagePageSaveJournal.Entry entry(String error) {
        return new ImagePageSaveJournal.Entry(
                "{\"id\":42,\"title\":\"多页\\n漫画\",\"page_count\":8}",
                3, "https://example.test/42_p3.jpg?a=1&b=2", 123456L, error);
    }

    @Test public void testPendingIntentSurvivesNewInstance() throws Exception {
        try (Fixture f = new Fixture()) {
            ImagePageSaveJournal.Entry wanted = entry(null);
            f.journal.write(Collections.singletonList(wanted));
            List<ImagePageSaveJournal.Entry> restored = new ImagePageSaveJournal(f.file).read();
            equal(1, restored.size());
            equal(wanted.illustJson, restored.get(0).illustJson);
            equal(wanted.imageUrl, restored.get(0).imageUrl);
            equal(wanted.page, restored.get(0).page);
            equal(wanted.queuedAt, restored.get(0).queuedAt);
            equal(null, restored.get(0).error);
        }
    }

    @Test public void testFailuresIncludingEmptyErrorRemainFailures() throws Exception {
        try (Fixture f = new Fixture()) {
            f.journal.write(Arrays.asList(entry("网络失败\npermission: denied"), entry("")));
            List<ImagePageSaveJournal.Entry> restored = new ImagePageSaveJournal(f.file).read();
            equal("网络失败\npermission: denied", restored.get(0).error);
            equal("", restored.get(1).error);
        }
    }

    @Test public void testClearedTasksDoNotReturnAfterRestart() throws Exception {
        try (Fixture f = new Fixture()) {
            f.journal.write(Collections.singletonList(entry(null)));
            f.journal.write(Collections.emptyList());
            equal(0, new ImagePageSaveJournal(f.file).read().size());
            equal(false, new File(f.file.getPath() + ".bak").exists());
        }
    }

    @Test public void testInterruptedReplacementRestoresLastCommittedSnapshot() throws Exception {
        try (Fixture f = new Fixture()) {
            f.journal.write(Collections.singletonList(entry("old failure")));
            check(f.file.renameTo(new File(f.file.getPath() + ".bak")), "Create interrupted-write backup");
            Files.write(f.file.toPath(), "partial replacement".getBytes("UTF-8"));
            Files.write(new File(f.file.getPath() + ".new").toPath(), "partial new".getBytes("UTF-8"));
            equal("old failure", new ImagePageSaveJournal(f.file).read().get(0).error);
        }
    }

    @Test public void testUncommittedFirstWriteIsNotAQueue() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.write(new File(f.file.getPath() + ".new").toPath(), "partial".getBytes("UTF-8"));
            equal(0, new ImagePageSaveJournal(f.file).read().size());
        }
    }

    @Test public void testInvalidIntentDoesNotReplaceCommittedQueue() throws Exception {
        try (Fixture f = new Fixture()) {
            f.journal.write(Collections.singletonList(entry(null)));
            expectIOException(() -> f.journal.write(Collections.singletonList(
                    new ImagePageSaveJournal.Entry("{}", -1, "", 1, null))));
            equal(3, new ImagePageSaveJournal(f.file).read().get(0).page);
        }
    }

    @Test public void testUnknownFormatIsKeptForRecovery() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] future = "format=99\ncount=1\n".getBytes("UTF-8");
            Files.write(f.file.toPath(), future);
            expectIOException(() -> f.journal.read());
            check(Arrays.equals(future, Files.readAllBytes(f.file.toPath())), "Preserve unreadable journal");
        }
    }

    @Test public void testRetryReplacesFailureInsteadOfAppendingIt() throws Exception {
        try (Fixture f = new Fixture()) {
            f.journal.write(Collections.singletonList(entry("failed")));
            ImagePageSaveJournal.Entry retry = new ImagePageSaveJournal.Entry(
                    entry(null).illustJson, 3, entry(null).imageUrl, 999999L, null);
            f.journal.write(Collections.singletonList(retry));
            List<ImagePageSaveJournal.Entry> restored = new ImagePageSaveJournal(f.file).read();
            equal(1, restored.size());
            equal(999999L, restored.get(0).queuedAt);
            equal(null, restored.get(0).error);
        }
    }

    private static void equal(Object expected, Object actual) {
        check(Objects.equals(expected, actual), "Expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private interface IoAction { void run() throws IOException; }
    private static void expectIOException(IoAction action) throws IOException {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected an IOException");
    }
    private static final class Fixture implements AutoCloseable {
        final File directory = Files.createTempDirectory("page-save-journal").toFile();
        final File file = new File(directory, "queue.properties");
        final ImagePageSaveJournal journal = new ImagePageSaveJournal(file);
        Fixture() throws IOException {}
        @Override public void close() {
            File[] files = directory.listFiles();
            if (files != null) for (File child : files) child.delete();
            directory.delete();
        }
    }
}
