package ceui.pixiv.download;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Durable save intents, separate from the completed-download database and image cache. */
public final class ImagePageSaveJournal {
    private final File file;
    private final File pending;
    private final File backup;

    public ImagePageSaveJournal(File file) {
        this.file = file;
        pending = new File(file.getPath() + ".new");
        backup = new File(file.getPath() + ".bak");
    }

    public static final class Entry {
        public final String illustJson;
        public final int page;
        public final String imageUrl;
        public final long queuedAt;
        // null = interrupted/pending; a non-null value (even empty) = a retained failure.
        public final String error;

        public Entry(String illustJson, int page, String imageUrl, long queuedAt, String error) {
            this.illustJson = illustJson;
            this.page = page;
            this.imageUrl = imageUrl;
            this.queuedAt = queuedAt;
            this.error = error;
        }
    }

    public synchronized List<Entry> read() throws IOException {
        restoreBackup();
        if (!file.exists()) return new ArrayList<>();
        Properties data = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            data.load(input);
        }
        if (!"1".equals(data.getProperty("format"))) {
            throw new IOException("Unsupported single-page save journal format");
        }
        List<Entry> entries = new ArrayList<>();
        try {
            int count = Integer.parseInt(required(data, "count"));
            if (count < 0 || count > 10000) throw new IllegalArgumentException("Invalid task count");
            for (int i = 0; i < count; i++) {
                String prefix = "task." + i + ".";
                Entry entry = new Entry(required(data, prefix + "illust"),
                        Integer.parseInt(required(data, prefix + "page")),
                        required(data, prefix + "url"),
                        Long.parseLong(required(data, prefix + "queuedAt")),
                        data.getProperty(prefix + "error"));
                validate(entry);
                entries.add(entry);
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid single-page save journal", ex);
        }
        return entries;
    }

    /** Commit only after the new snapshot is flushed; a killed writer leaves the old snapshot. */
    public synchronized void write(List<Entry> entries) throws IOException {
        Properties data = new Properties();
        data.setProperty("format", "1");
        if (entries.size() > 10000) throw new IOException("Too many single-page saves");
        data.setProperty("count", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            validate(entry);
            String prefix = "task." + i + ".";
            data.setProperty(prefix + "illust", entry.illustJson);
            data.setProperty(prefix + "page", Integer.toString(entry.page));
            data.setProperty(prefix + "url", entry.imageUrl);
            data.setProperty(prefix + "queuedAt", Long.toString(entry.queuedAt));
            if (entry.error != null) data.setProperty(prefix + "error", entry.error);
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create single-page save directory");
        }
        restoreBackup();
        try {
            try (FileOutputStream output = new FileOutputStream(pending)) {
                data.store(output, "Single-page save intents");
                output.getFD().sync();
            }
            if (file.exists() && !file.renameTo(backup)) throw new IOException("Cannot back up save journal");
            if (!pending.renameTo(file)) throw new IOException("Cannot replace save journal");
            if (backup.exists() && !backup.delete()) throw new IOException("Cannot commit save journal");
        } catch (IOException ex) {
            try {
                restoreBackup();
            } catch (IOException rollback) {
                ex.addSuppressed(rollback);
            }
            throw ex;
        } finally {
            // An uncommitted .new is never read as a queue after restart.
            if (pending.exists()) pending.delete();
        }
    }

    private void restoreBackup() throws IOException {
        if (!backup.exists()) return;
        if (file.exists() && !file.delete()) throw new IOException("Cannot discard interrupted save journal");
        if (!backup.renameTo(file)) throw new IOException("Cannot restore save journal backup");
    }

    private static String required(Properties data, String name) {
        String value = data.getProperty(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private static void validate(Entry entry) throws IOException {
        if (entry.illustJson == null || entry.illustJson.isEmpty() || entry.page < 0
                || entry.imageUrl == null || entry.imageUrl.trim().isEmpty() || entry.queuedAt <= 0) {
            throw new IOException("Invalid single-page save intent");
        }
    }
}
