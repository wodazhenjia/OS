package cn.edu.scau.os.app;

import cn.edu.scau.os.kernel.Kernel;

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

    /** 恢复结果：可用的内核 + 坏盘备份文件。 */
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
        move(diskFile, backup);
        Kernel kernel = new Kernel(diskFile);
        try {
            kernel.initialize(BOOT_SEED);
        } catch (RuntimeException failure) {
            try {
                Files.move(backup, diskFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
        return new Recovery(kernel, backup);
    }

    /** 生成不与现有文件冲突的备份名。 */
    static Path uniqueBackup(Path diskFile, Instant now) {
        String stamp = STAMP.format(now);
        Path candidate = diskFile.resolveSibling("disk-broken-" + stamp + ".img");
        for (int index = 1; Files.exists(candidate); index++) {
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
