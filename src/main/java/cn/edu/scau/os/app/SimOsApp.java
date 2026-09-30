package cn.edu.scau.os.app;

import cn.edu.scau.os.disk.FileSystem;
import cn.edu.scau.os.instruction.Assembler;
import cn.edu.scau.os.kernel.Kernel;
import cn.edu.scau.os.ui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SimOS 启动入口。
 *
 * <pre>
 * 用法：
 *   java -jar target/simos.jar                  启动图形界面（默认）
 *   java -jar target/simos.jar --selftest [N]    无界面自检，N 为推进的时间单位数（默认 400）
 *   java -jar target/simos.jar --help            显示帮助
 * </pre>
 */
public final class SimOsApp {

    private static final String DISK_FILE = "runtime/disk.img";

    private SimOsApp() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case "--help", "-h" -> {
                System.out.println("""
                        SimOS —— 操作系统分析与设计实习 题目六：模拟操作系统实现（原型）
                        用法：
                          java -jar simos.jar                 启动图形界面
                          java -jar simos.jar --selftest [N]  无界面自检，N 为推进的时间单位数（默认 400）
                          java -jar simos.jar --help          显示本帮助
                        模拟磁盘文件：%s
                        """.formatted(Paths.get(DISK_FILE).toAbsolutePath()));
                return;
            }
            case "--selftest" -> {
                int ticks = args.length > 1 ? Integer.parseInt(args[1]) : 400;
                selfTest(ticks);
                return;
            }
            default -> launchGui();
        }
    }

    private static void launchGui() throws Exception {
        Path disk = resolveDiskFile();
        Kernel kernel = new Kernel(disk);
        kernel.initialize(20260501);
        kernel.start(500);

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignore) {
                // 使用默认外观
            }
            MainFrame f = new MainFrame(kernel);
            f.setVisible(true);
        });
        System.out.println("SimOS 已启动。模拟磁盘文件：" + disk.toAbsolutePath());
    }

    private static Path resolveDiskFile() {
        Path p = Paths.get(DISK_FILE);
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            return p;
        } catch (Exception e) {
            Path tmp = Paths.get(System.getProperty("java.io.tmpdir"), "simos-disk.img");
            System.out.println("无法使用 " + p.toAbsolutePath() + "，改用 " + tmp);
            return tmp;
        }
    }

    // ==================================================================
    // 无界面自检
    // ==================================================================

    private static void selfTest(int ticks) {
        System.out.println("================ SimOS 自检开始 ================");
        Path disk = resolveDiskFile();
        Kernel kernel = new Kernel(disk);
        int pass = 0;
        int fail = 0;

        // ---- 1. 指令编码与汇编 ----
        System.out.println("\n[1] 指令集与汇编器");
        Assembler.Result r = Assembler.assemble("""
                x=5
                x++
                !A3
                x--
                end
                """);
        report(r.ok(), "汇编无错误：" + r.errors());
        if (r.ok()) {
            pass++;
        } else {
            fail++;
        }
        byte[] expect = {0x05, 0x40, 0x42, 0x03, 0x41, 0x45};
        boolean same = r.code().length == expect.length;
        for (int i = 0; same && i < expect.length; i++) {
            same = r.code()[i] == expect[i];
        }
        report(same, "机器码 = " + hex(r.code()) + "（期望 " + hex(expect) + "）");
        if (same) {
            pass++;
        } else {
            fail++;
        }
        String dis = Assembler.disassemble(expect);
        report(dis.contains("x=5") && dis.contains("!A? 3") && dis.contains("end"), "反汇编：\n" + dis);
        if (dis.contains("x=5") && dis.contains("!A? 3")) {
            pass++;
        } else {
            fail++;
        }
        Assembler.Result bad = Assembler.assemble("x=99");
        report(!bad.ok(), "越界赋值被拒绝：" + bad.errors());
        if (!bad.ok()) {
            pass++;
        } else {
            fail++;
        }
        Assembler.Result tooLong = Assembler.assemble("x=1\n!A200\nend");
        report(!tooLong.ok(), "设备时间越界被拒绝：" + tooLong.errors());
        if (!tooLong.ok()) {
            pass++;
        } else {
            fail++;
        }

        // ---- 2. 磁盘与文件系统 ----
        System.out.println("\n[2] 磁盘、FAT、目录与命令");
        kernel.initialize(20260501);
        FileSystem fs = kernel.fs();
        long rev0 = fs.revisions();
        int progs = kernel.executableFiles().size();
        report(progs >= 10, "可执行文件数量 = " + progs + "（要求约 10 个）");
        if (progs >= 10) {
            pass++;
        } else {
            fail++;
        }
        int dirs = 0;
        int files = 0;
        for (FileSystem.DirNode n : fs.rootTree().children()) {
            if (n.directory()) {
                dirs++;
                files += countFiles(n);
            } else {
                files++;
            }
        }
        report(dirs >= 5 && files >= 15, "目录数 = " + dirs + "（≥5），文件数 = " + files + "（≥15）");
        if (dirs >= 5 && files >= 15) {
            pass++;
        } else {
            fail++;
        }
        // 根目录已被 5 个目录 + 2 个可执行文件占用（7 项），还剩 1 个空位
        report(fs.listRoot().size() <= 8, "根目录登记项 = " + fs.listRoot().size() + "（上限 8）");

        Kernel.CommandResult cr = kernel.exec("mkdir /dd/t3t");
        report(cr.ok(), "mkdir /dd/t3t → " + cr.message());
        if (cr.ok()) {
            pass++;
        } else {
            fail++;
        }
        cr = kernel.exec("mkdir /dd/t3t");
        report(!cr.ok(), "重复建立同名目录被拒绝 → " + oneLine(cr.message()));
        if (!cr.ok()) {
            pass++;
        } else {
            fail++;
        }
        cr = kernel.exec("create /dd/t3t/aaa hello world");
        report(cr.ok(), "create /dd/t3t/aaa → " + cr.message());
        cr = kernel.exec("type /dd/t3t/aaa");
        report(cr.ok() && cr.message().contains("hello world"), "type /dd/t3t/aaa 内容正确");
        if (cr.ok() && cr.message().contains("hello world")) {
            pass++;
        } else {
            fail++;
        }
        cr = kernel.exec("copy /dd/t3t/aaa /dd/t3t/bbb t");
        report(cr.ok(), "copy → " + cr.message());
        if (cr.ok()) {
            pass++;
        } else {
            fail++;
        }
        cr = kernel.exec("rmdir /dd/t3t");
        report(!cr.ok() && cr.message().contains("非空"), "非空目录 rmdir 被拒绝 → " + oneLine(cr.message()));
        if (!cr.ok()) {
            pass++;
        } else {
            fail++;
        }
        cr = kernel.exec("delete /dd/t3t/aaa");
        report(cr.ok(), "delete /dd/t3t/aaa → " + cr.message());
        cr = kernel.exec("delete /dd/t3t/bbb.t");
        report(cr.ok(), "delete /dd/t3t/bbb.t → " + cr.message());
        cr = kernel.exec("rmdir /dd/t3t");
        report(cr.ok(), "空目录 rmdir → " + cr.message());
        if (cr.ok()) {
            pass++;
        } else {
            fail++;
        }
        cr = kernel.exec("create /toolong x");
        report(!cr.ok(), "超长文件名被拒绝：" + oneLine(cr.message()));
        if (!cr.ok()) {
            pass++;
        } else {
            fail++;
        }
        cr = kernel.exec("create /aa/bad.e x=1|x++|!A3|end");
        report(cr.ok(), "用汇编源码创建 /aa/bad.e → " + cr.message());
        if (cr.ok()) {
            pass++;
        } else {
            fail++;
        }
        cr = kernel.exec("type /aa/bad.e");
        report(cr.ok() && cr.message().contains("!A? 3"), "type /aa/bad.e 显示反汇编");
        if (cr.ok() && cr.message().contains("!A? 3")) {
            pass++;
        } else {
            fail++;
        }
        cr = kernel.exec("delete /hi.e");
        report(cr.ok(), "delete /hi.e → " + cr.message());
        report(fs.revisions() > rev0, "文件系统修改计数递增 = " + fs.revisions());

        // ---- 3. 进程创建、内存、调度、设备、中断 ----
        System.out.println("\n[3] 进程 / 内存 / 设备 / 调度 / 中断");
        int created = kernel.create("/lo.e");
        report(created >= 0, "创建进程 P" + created + "（/lo.e）");
        if (created >= 0) {
            pass++;
        } else {
            fail++;
        }
        Kernel.Snapshot s0 = kernel.snapshot();
        boolean memOk = false;
        for (var part : s0.memoryPartitions()) {
            if (part.pid == created) {
                memOk = true;
            }
        }
        report(memOk, "内存已分配给 P" + created + "：空闲 " + s0.memoryFree() + " 字节");
        if (memOk) {
            pass++;
        } else {
            fail++;
        }

        // 推进模拟：直接手动跑 cycle()，不需要真线程，结果可复现
        kernel.cpu().setPaused(true);
        for (int i = 0; i < ticks; i++) {
            synchronized (kernel.lock()) {
                kernel.cpu().cycle();
            }
        }
        Kernel.Snapshot s1 = kernel.snapshot();
        System.out.println();
        System.out.println("推进 " + ticks + " 个时间单位后：");
        System.out.println("  系统时钟 = " + s1.systemClock());
        System.out.println("  正在运行 = " + s1.runningInfo());
        System.out.println("  就绪队列 = " + s1.readyQueue());
        System.out.println("  阻塞队列 = " + s1.blockedQueue());
        System.out.println("  空闲 PCB = " + s1.freeQueue());
        System.out.println("  内存空闲 = " + s1.memoryFree() + " 字节");
        System.out.println("  已用盘块 = " + s1.usedBlocks() + "，空闲盘块 = " + s1.freeBlocks());
        System.out.println("  执行完的进程结果：");
        for (String line : s1.results()) {
            System.out.println("    " + line);
        }
        report(!s1.results().isEmpty(), "至少有进程执行结束并输出结果");
        if (!s1.results().isEmpty()) {
            pass++;
        } else {
            fail++;
        }
        boolean anyDeviceUsed = s1.logLines().stream().anyMatch(l -> l.contains("设备"));
        report(anyDeviceUsed, "设备管理已被触发（分配/等待/释放出现于日志）");
        if (anyDeviceUsed) {
            pass++;
        } else {
            fail++;
        }
        boolean schedHappened = s1.logLines().stream().anyMatch(l -> l.contains("时间片到") || l.contains("调度"));
        report(schedHappened, "时间片轮转调度已发生");
        if (schedHappened) {
            pass++;
        } else {
            fail++;
        }
        boolean ioInterrupt = s1.logLines().stream().anyMatch(l -> l.contains("I/O 中断"));
        report(ioInterrupt, "I/O 中断（设备倒计时到 0）已发生");
        if (ioInterrupt) {
            pass++;
        } else {
            fail++;
        }

        System.out.println("\n---- 最近日志 ----");
        for (String l : s1.logLines()) {
            System.out.println("  " + l);
        }

        System.out.println("\n================ 自检结果：通过 " + pass + " 项，失败 " + fail + " 项 ================");
        System.out.println("模拟磁盘文件：" + disk.toAbsolutePath());
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static int countFiles(FileSystem.DirNode dir) {
        int n = 0;
        for (FileSystem.DirNode c : dir.children()) {
            if (c.directory()) {
                n += countFiles(c);
            } else {
                n++;
            }
        }
        return n;
    }

    private static void report(boolean ok, String msg) {
        System.out.println("  " + (ok ? "[OK] " : "[!!] ") + msg);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02X ", x));
        }
        return sb.toString().trim();
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ');
    }
}
