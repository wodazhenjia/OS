package cn.edu.scau.os.app;

import cn.edu.scau.os.kernel.Kernel;
import cn.edu.scau.os.disk.PartitionManager;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 坏盘恢复的纯逻辑：把无法加载的磁盘备份走，再重建一份演示数据。
 *
 * <p>这里不弹任何对话框——"是否恢复"由 {@link SimOsApp} 询问用户，本类只负责：
 * 原盘改名保留 → 新建内核 → 格式化并播种。这样恢复流程可以在无界面环境里测试。</p>
 */
public final class SafeBoot {

    /** 演示数据种子：固定值保证恢复后的目录、文件与程序可复现。 */
    public static final int BOOT_SEED = 20260501;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /** 恢复结果：可用内核与现存数据的备份文件（镜像缺失时返回分区表备份）。 */
    public record Recovery(Kernel kernel, Path backup) {
    }

    private SafeBoot() {
    }

    /**
     * 把无法加载的磁盘改名为 {@code disk-broken-时间戳.img}（原字节完整保留），
     * 然后在原位置重建演示数据。
     *
     * <p>重建失败时会把备份移回原位并抛出原异常，尽量不让用户同时失去两份数据。</p>
     */
    public static Recovery recover(Path diskFile, Instant now) throws IOException {
        Path backup = uniqueBackup(diskFile, now);
        Path table = PartitionManager.tablePath(diskFile);
        Path tableBackup = PartitionManager.tablePath(backup);
        if (!Files.exists(diskFile) && !Files.exists(table))
            throw new java.nio.file.NoSuchFileException(diskFile.toString());
        boolean movedDisk = false;
        boolean movedTable = false;
        try {
            if (Files.exists(diskFile)) { move(diskFile, backup); movedDisk = true; }
            if (Files.exists(table)) { move(table, tableBackup); movedTable = true; }
            Kernel kernel = new Kernel(diskFile);
            kernel.initialize(BOOT_SEED);
            return new Recovery(kernel, movedDisk ? backup : tableBackup);
        } catch (RuntimeException | IOException failure) {
            // 分别恢复，避免镜像回滚失败时连分区表也不尝试恢复。
            try {
                if (movedDisk) Files.move(backup, diskFile, StandardCopyOption.REPLACE_EXISTING);
                else if (movedTable) Files.deleteIfExists(diskFile);
            } catch (IOException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            if (movedTable) {
                try { Files.move(tableBackup, table, StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException restoreFailure) { failure.addSuppressed(restoreFailure); }
            }
            throw failure;
        }
    }

    /** 生成不与现有文件冲突的备份名。 */
    static Path uniqueBackup(Path diskFile, Instant now) {
        String stamp = STAMP.format(now);
        Path candidate = diskFile.resolveSibling("disk-broken-" + stamp + ".img");
        for (int index = 1; Files.exists(candidate) || Files.exists(PartitionManager.tablePath(candidate)); index++) {
            candidate = diskFile.resolveSibling("disk-broken-" + stamp + "-" + index + ".img");
        }
        return candidate;
    }

    private static void move(Path from, Path to) throws IOException {
        Path parent = to.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | UnsupportedOperationException notAtomic) {
            Files.move(from, to);
        }
    }
}
