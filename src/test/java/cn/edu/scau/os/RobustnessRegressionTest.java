package cn.edu.scau.os;

import cn.edu.scau.os.app.CrashReporter;
import cn.edu.scau.os.app.SafeBoot;
import cn.edu.scau.os.kernel.Kernel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

/**
 * 健壮性回归：崩溃日志、坏盘恢复、危险命令判定、重复关机。
 *
 * <p>与项目其他测试一致，不依赖 JUnit，失败以非零状态退出。所有磁盘都在临时目录内。</p>
 */
public final class RobustnessRegressionTest {

    private static Path temporary;
    private static int assertions;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        temporary = Files.createTempDirectory("simos-robustness-");
        crashLog();
        corruptDiskRecovery();
        dangerousCommands();
        repeatedShutdown();
        System.out.println("Robustness regression passed: " + assertions + " assertions, 4 groups");
    }

    private static void check(boolean result, String message) {
        assertions++;
        if (!result) {
            throw new AssertionError(message);
        }
    }

    /** 全局处理器必须把线程、异常类型与堆栈都写进日志，并且可以继续追加。 */
    private static void crashLog() throws Exception {
        Path log = temporary.resolve("errors.log");
        CrashReporter.install(log);
        Thread probe = new Thread(() -> {
            throw new IllegalStateException("crash-probe-boom");
        }, "crash-probe");
        probe.start();
        probe.join(5000);
        check(!probe.isAlive(), "crash probe finished");
        String text = Files.readString(log);
        check(text.contains("crash-probe-boom"), "crash message logged");
        check(text.contains("crash-probe"), "crash thread logged");
        check(text.contains("IllegalStateException"), "crash type logged");
        check(text.contains("at "), "stack trace logged");

        CrashReporter.record("second-record", new IllegalArgumentException("second-message"));
        check(Files.readString(log).contains("second-message"), "record appends to log");
    }

    /** 坏盘必须先被拒绝、保持原字节；恢复后备份完整、演示数据齐全。 */
    private static void corruptDiskRecovery() throws Exception {
        Path dir = Files.createTempDirectory(temporary, "recover-");
        Path disk = dir.resolve("disk.img");
        byte[] broken = new byte[100];
        Arrays.fill(broken, (byte) 7);
        Files.write(disk, broken);

        Kernel brokenKernel = new Kernel(disk);
        boolean rejected = false;
        try {
            brokenKernel.boot(42);
        } catch (RuntimeException expected) {
            rejected = true;
        }
        check(rejected, "corrupt disk rejected");
        check(Arrays.equals(Files.readAllBytes(disk), broken), "corrupt disk not modified");

        SafeBoot.Recovery recovery = SafeBoot.recover(disk, Instant.now());
        check(Files.exists(recovery.backup()), "backup created");
        check(Arrays.equals(Files.readAllBytes(recovery.backup()), broken), "backup keeps original bytes");
        check(Files.size(disk) == 16_384, "rebuilt disk size");
        check(recovery.backup().getFileName().toString().startsWith("disk-broken-"), "backup naming");
        recovery.kernel().fs().validate();
        check(recovery.kernel().executableFiles().size() >= 10, "demo executables rebuilt");
        check(recovery.kernel().fs().rootTree().children().size() >= 5, "demo directories rebuilt");

        // 同一目录再恢复一次，备份名不能覆盖前一份
        Path disk2 = dir.resolve("disk2.img");
        Files.write(disk2, broken);
        SafeBoot.Recovery second = SafeBoot.recover(disk2, Instant.now());
        check(!second.backup().equals(recovery.backup()), "backup names unique");
        check(Files.exists(recovery.backup()), "first backup kept");
        check(Arrays.equals(Files.readAllBytes(recovery.backup()), broken), "first backup intact");
    }

    /** 只有 format 与 deldir / 需要二次确认，普通文件/目录操作不能被误拦。 */
    private static void dangerousCommands() {
        for (String line : new String[]{"format", "  FORMAT  ", "format /x", "deldir /", "deldir \\", "deldir /./"}) {
            check(Kernel.isDestructiveCommand(line), "destructive: " + line);
        }
        for (String line : new String[]{"", "   ", "deldir /aa", "deldir /aa/", "delete /x", "create /aa/x",
                "copy /aa/x /aa/y", "mkdir /zz", "rmdir /zz", "run /hi.e", "dir", "help"}) {
            check(!Kernel.isDestructiveCommand(line), "safe: " + line);
        }
        check(Kernel.destructiveDetail("format").contains("format"), "format detail");
        check(Kernel.destructiveDetail("deldir /").contains("deldir"), "deldir detail");
    }

    /** 退出路径会重复调用 shutdown：必须幂等，且线程确实停下。 */
    private static void repeatedShutdown() throws Exception {
        Kernel kernel = new Kernel(temporary.resolve("shutdown.img"));
        kernel.initialize(42);
        kernel.shutdown();
        kernel.shutdown();
        kernel.start(20);
        Thread.sleep(150);
        kernel.shutdown();
        kernel.shutdown();
        check(!kernel.cpu().isRunning(), "cpu stopped after repeated shutdown");
    }
}
