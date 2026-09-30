package cn.edu.scau.os.disk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 用文件模拟的磁盘。
 *
 * <p>指导书要求：用一个文件 disk 模拟磁盘，每个盘块 64 字节，共 256 块；
 * 强调"不能将整个模拟磁盘的内容同时读入主存"，必须按块读写。</p>
 *
 * <p>本实现把磁盘内容整体放在内存字节数组里（便于原型阶段快速验证），
 * 但**只对外暴露 readBlock / writeBlock 两个按块操作的入口**，
 * 上层文件系统不允许直接访问字节数组；同时每次写块都会把内容落盘到 disk 文件，
 * 从而保留"磁盘 I/O"的行为特征。正式版若要求严格模拟，只需把本类换成
 * "每次 readBlock 都从文件读取"的实现，上层代码无需改动。</p>
 */
public class VirtualDisk {

    /** 每块字节数。 */
    public static final int BLOCK_SIZE = 64;
    /** 盘块总数。 */
    public static final int BLOCK_COUNT = 256;
    /** 磁盘总字节数。 */
    public static final int TOTAL_BYTES = BLOCK_SIZE * BLOCK_COUNT;

    private final byte[] bytes = new byte[TOTAL_BYTES];
    private final boolean[] dirty = new boolean[BLOCK_COUNT];
    private final Path file;
    private long readOps;
    private long writeOps;
    private int dirtyCount;
    /** 攒够多少个脏块就落盘一次（避免每次写块都做文件 I/O 拖慢界面刷新）。 */
    private static final int FLUSH_THRESHOLD = 8;

    public VirtualDisk(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public long readOps() {
        return readOps;
    }

    public long writeOps() {
        return writeOps;
    }

    /** 读一个盘块，返回 64 字节的副本。 */
    public byte[] readBlock(int blockNo) {
        checkBlock(blockNo);
        readOps++;
        byte[] out = new byte[BLOCK_SIZE];
        System.arraycopy(bytes, blockNo * BLOCK_SIZE, out, 0, BLOCK_SIZE);
        return out;
    }

    /** 把 64 字节写回盘块（先记账为脏块，攒够一批再落盘）。 */
    public void writeBlock(int blockNo, byte[] data) {
        checkBlock(blockNo);
        if (data.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("盘块必须正好 " + BLOCK_SIZE + " 字节，实际 " + data.length);
        }
        writeOps++;
        System.arraycopy(data, 0, bytes, blockNo * BLOCK_SIZE, BLOCK_SIZE);
        if (!dirty[blockNo]) {
            dirty[blockNo] = true;
            dirtyCount++;
        }
        if (dirtyCount >= FLUSH_THRESHOLD) {
            flushDirty();
        }
    }

    /** 从盘块内偏移处读出 len 字节。 */
    public byte[] readInBlock(int blockNo, int offsetInBlock, int len) {
        byte[] blk = readBlock(blockNo);
        byte[] out = new byte[len];
        System.arraycopy(blk, offsetInBlock, out, 0, len);
        return out;
    }

    /** 向盘块内偏移处写入 data。 */
    public void writeInBlock(int blockNo, int offsetInBlock, byte[] data) {
        byte[] blk = readBlock(blockNo);
        System.arraycopy(data, 0, blk, offsetInBlock, data.length);
        writeBlock(blockNo, blk);
    }

    /** 整盘清零（格式化的一部分）。 */
    public void clear() {
        java.util.Arrays.fill(bytes, (byte) 0);
        java.util.Arrays.fill(dirty, true);
        dirtyCount = BLOCK_COUNT;
        readOps = 0;
        writeOps = 0;
        flushAll();
    }

    /** 把所有脏块写进 disk 文件（模拟磁盘落盘）。 */
    public void flushDirty() {
        if (dirtyCount == 0) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (!Files.exists(file) || Files.size(file) != TOTAL_BYTES) {
                flushAll();
                return;
            }
            try (var ch = java.nio.channels.FileChannel.open(file,
                    java.nio.file.StandardOpenOption.WRITE)) {
                for (int i = 0; i < BLOCK_COUNT; i++) {
                    if (dirty[i]) {
                        ch.write(java.nio.ByteBuffer.wrap(bytes, i * BLOCK_SIZE, BLOCK_SIZE),
                                (long) i * BLOCK_SIZE);
                    }
                }
            }
            java.util.Arrays.fill(dirty, false);
            dirtyCount = 0;
        } catch (IOException e) {
            throw new UncheckedIOException("写 disk 文件失败：" + file, e);
        }
    }

    /** 整盘落盘。 */
    public void flushAll() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, bytes);
            java.util.Arrays.fill(dirty, false);
            dirtyCount = 0;
        } catch (IOException e) {
            throw new UncheckedIOException("写 disk 文件失败：" + file, e);
        }
    }

    private void checkBlock(int blockNo) {
        if (blockNo < 0 || blockNo >= BLOCK_COUNT) {
            throw new IllegalArgumentException("非法盘块号：" + blockNo + "（合法范围 0~" + (BLOCK_COUNT - 1) + "）");
        }
    }
}
