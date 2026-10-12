package cn.edu.scau.os.disk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** 按块访问真实 disk 文件，仅使用64字节缓冲，不把整盘载入内存。 */
public class VirtualDisk {
    public static final int BLOCK_SIZE = 64;
    public static final int BLOCK_COUNT = 256;
    public static final int TOTAL_BYTES = BLOCK_SIZE * BLOCK_COUNT;
    private final Path file;
    private long readOps;
    private long writeOps;

    public VirtualDisk(Path file) { this.file = file.toAbsolutePath(); }
    public Path file() { return file; }
    public long readOps() { return readOps; }
    public long writeOps() { return writeOps; }
    public boolean exists() { return Files.exists(file); }

    public void validateSize() {
        try {
            if (Files.size(file) != TOTAL_BYTES) throw new IllegalStateException("模拟磁盘长度错误：" + file);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public byte[] readBlock(int blockNo) {
        checkBlock(blockNo);
        byte[] block = new byte[BLOCK_SIZE];
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(block);
            long position = (long) blockNo * BLOCK_SIZE;
            while (buffer.hasRemaining()) {
                int n = ch.read(buffer, position);
                if (n < 0) throw new IOException("盘块内容不完整：" + blockNo);
                position += n;
            }
            readOps++;
            return block;
        } catch (IOException e) { throw new UncheckedIOException("读磁盘失败：" + file, e); }
    }
    public void writeBlock(int blockNo, byte[] data) {
        checkBlock(blockNo);
        if (data.length != BLOCK_SIZE) throw new IllegalArgumentException("盘块必须为64字节");
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            long position = (long) blockNo * BLOCK_SIZE;
            while (buffer.hasRemaining()) position += ch.write(buffer, position);
            writeOps++;
        } catch (IOException e) { throw new UncheckedIOException("写磁盘失败：" + file, e); }
    }
    public byte[] readInBlock(int block, int offset, int length) {
        checkRange(offset, length);
        return java.util.Arrays.copyOfRange(readBlock(block), offset, offset + length);
    }
    public void writeInBlock(int block, int offset, byte[] data) {
        checkRange(offset, data.length);
        byte[] buffer = readBlock(block);
        System.arraycopy(data, 0, buffer, offset, data.length);
        writeBlock(block, buffer);
    }
    public void clear() {
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer zero = ByteBuffer.wrap(new byte[BLOCK_SIZE]);
                for (int i = 0; i < BLOCK_COUNT; i++) {
                    zero.clear();
                    while (zero.hasRemaining()) ch.write(zero);
                }
                ch.force(true);
            }
            readOps = writeOps = 0;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    /** 文件操作撤销副本放在文件系统中，不占用模拟主存。 */
    public Path backup(String prefix) {
        try {
            Path backup = Files.createTempFile(file.getParent(), prefix, ".bak");
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public void restore(Path backup) {
        try { Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public void discardBackup(Path backup) {
        try { Files.deleteIfExists(backup); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public void flushDirty() {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) { ch.force(true); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public void flushAll() { flushDirty(); }
    private void checkBlock(int block) {
        if (block < 0 || block >= BLOCK_COUNT) throw new IllegalArgumentException("非法盘块号：" + block);
    }
    private void checkRange(int offset, int length) {
        if (offset < 0 || length < 0 || (long) offset + length > BLOCK_SIZE)
            throw new IllegalArgumentException("盘块访问越界");
    }
}
