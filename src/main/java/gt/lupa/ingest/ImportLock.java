package gt.lupa.ingest;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;

public final class ImportLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private ImportLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static ImportLock acquire(StorageLayout layout) throws IngestException {
        layout.initialize();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(
                    layout.lockFile(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new IngestException(5, "another LUPA import is already running");
            }
            return new ImportLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            closeQuietly(channel);
            throw new IngestException(5, "another LUPA import is already running", e);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new IngestException(4, "cannot acquire importer lock", e);
        }
    }

    @Override
    public void close() throws IngestException {
        IOException failure = null;
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException e) {
            failure = e;
        }
        try {
            channel.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw new IngestException(4, "cannot release importer lock", failure);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }
}
