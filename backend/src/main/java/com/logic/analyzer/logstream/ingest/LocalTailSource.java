package com.logic.analyzer.logstream.ingest;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

public class LocalTailSource implements TailSource {

    private final Path path;

    public LocalTailSource(Path path) {
        this.path = path;
    }

    @Override
    public TailBytes readTail(int maxBytes) throws IOException {
        long size = Files.size(path);
        long offset = Math.max(0, size - maxBytes);
        int length = (int) (size - offset);
        byte[] buf = new byte[length];
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            raf.seek(offset);
            raf.readFully(buf);
        }
        return new TailBytes(buf, offset == 0);
    }

    @Override
    public Fingerprint probe() throws IOException {
        return new Fingerprint(Files.size(path), Files.getLastModifiedTime(path).toInstant());
    }
}
