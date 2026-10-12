package cn.edu.scau.os.kernel;

import cn.edu.scau.os.cpu.CPU;
import cn.edu.scau.os.device.DeviceManager;
import cn.edu.scau.os.disk.DiskLayout;
import cn.edu.scau.os.disk.FileEntry;
import cn.edu.scau.os.disk.FileSystem;
import cn.edu.scau.os.disk.VirtualDisk;
import cn.edu.scau.os.instruction.Assembler;
import cn.edu.scau.os.process.BlockReason;
import cn.edu.scau.os.process.PCB;
import cn.edu.scau.os.process.ProcessState;
import cn.edu.scau.os.process.Scheduler;
import cn.edu.scau.os.storage.MemoryManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * 模拟操作系统内核：把文件管理、存储管理、设备管理、进程管理与用户接口接成一条可运行的链。
 *
 * <h2>并发模型</h2>
 * <p>指导书提示"可能需要多线程编程"才能保证 CPU 执行、系统时钟递增、时间片递减、
 * 设备倒计时递减、随机新进程诞生这几种任务同时存在。本原型把它们放在<b>同一个时间脉冲</b>里，
 * 由 {@link CPU#CPU()} 所在的线程驱动，脉冲内部按固定顺序推进：</p>
 * <pre>
 *   ① 已有设备倒计时   ② 中断检查与处理   ③ 随机到达和调度
 *   ④ 执行一条指令并结算实际执行者    ⑤ 系统时钟递增
 * </pre>
 * <p>整个脉冲持有唯一的模拟锁 {@link #lock()}，因此界面读取快照、用户命令修改磁盘时
 * 都不会读到"半个操作"。这既满足并发语义，又保证演示结果可复现。</p>
 */
public class Kernel implements CPU.Host {

    private final Object lock = new Object();
    private final VirtualDisk disk;
    private final FileSystem fs;
    private final MemoryManager memory = new MemoryManager();
    private final DeviceManager devices = new DeviceManager();
    private final Scheduler scheduler = new Scheduler();
    private final CPU cpu;
    private final Random random = new Random(20260501L);

    /** 运行日志（进程创建、调度、设备分配、命令执行……）。 */
    public record LogEntry(long sequence, String text) { }
    private final Deque<LogEntry> log = new ArrayDeque<>();
    private long logSequence;
    private long cachedFsRevision = -1;
    private FileSystem.DirNode cachedRoot;
    private List<String> cachedExecutables = List.of();
    private static final int LOG_LIMIT = 400;

    /** 系统时钟：开机以来的时间单位数。 */
    private int systemClock;
    private int arriveCountdown;
    private boolean autoArrivals = true;

    /** 系统区与用户区的划分（指导书：系统区放 PCB 与内存分配表，用户区放可执行文件）。 */
    public static final int PCB_AREA_CAPACITY = Scheduler.MAX_PROCESS;

    public Kernel(Path diskFile) {
        this.disk = new VirtualDisk(diskFile);
        this.fs = new FileSystem(disk);
        this.cpu = new CPU(scheduler, memory, devices, fs, this, lock);
        setupIdleProcess();
    }

    public Object lock() {
        return lock;
    }

    public FileSystem fs() {
        return fs;
    }

    public VirtualDisk disk() {
        return disk;
    }

    public MemoryManager memory() {
        return memory;
    }

    public DeviceManager devices() {
        return devices;
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    public CPU cpu() {
        return cpu;
    }

    public int systemClock() {
        return systemClock;
    }

    public List<String> logLines(int max) {
        synchronized (lock) {
            return log.stream().skip(Math.max(0, log.size() - max)).map(LogEntry::text).toList();
        }
    }
    public List<LogEntry> logEntriesAfter(long sequence) {
        synchronized (lock) { return log.stream().filter(e -> e.sequence() > sequence).toList(); }
    }
    public void log(String message) {
        synchronized (lock) {
            log.addLast(new LogEntry(++logSequence, String.format("[t=%04d] %s", systemClock, message)));
            while (log.size() > LOG_LIMIT) log.removeFirst();
        }
    }
    public void setAutoArrivals(boolean enabled) {
        synchronized (lock) { autoArrivals = enabled; }
    }
    public boolean autoArrivalsEnabled() {
        synchronized (lock) { return autoArrivals; }
    }
    public void setMemoryFit(MemoryManager.Fit fit) {
        synchronized (lock) { memory.setFit(fit); }
    }

    // ==================================================================
    // 初始化
    // ==================================================================

    /** 正常启动加载已有磁盘；仅首次启动建立演示数据。 */
    public void boot(int seed) {
        synchronized (lock) {
            if (disk.exists()) fs.load();
            else { fs.format(); SeedBuilder.build(fs); }
            resetRuntime(seed);
        }
    }
    /** 用户显式重新初始化/格式化时重建演示数据。 */
    public void initialize(int seed) {
        synchronized (lock) {
            fs.format();
            SeedBuilder.build(fs);
            resetRuntime(seed);
        }
    }
    private void resetRuntime(int seed) {
        random.setSeed(seed);
        scheduler.reset(); memory.reset(); devices.reset(); cpu.reset();
        systemClock = 0;
        arriveCountdown = 2 + random.nextInt(5);
        pcbFullNoted = false;
        results.clear(); log.clear();
        setupIdleProcess();
        scheduler.setRunningPid(Scheduler.IDLE_PID);
        scheduler.pcb(Scheduler.IDLE_PID).state = ProcessState.RUNNING;
        cpu.restoreContext(scheduler.pcb(Scheduler.IDLE_PID));
        log("系统初始化完成；" + DiskLayout.describe());
    }

    /**
     * 建立闲逛进程（pid = 0）：就绪队列为空时占用 CPU，有进程就绪时立即让位。
     * 它什么有用的事也不做，只起"系统能正常运转"的作用。
     */
    private void setupIdleProcess() {
        PCB idle = scheduler.pcb(Scheduler.IDLE_PID);
        idle.reset();
        idle.pid = Scheduler.IDLE_PID;
        idle.name = "闲逛进程";
        idle.state = ProcessState.READY;
        idle.blockReason = BlockReason.IDLE;
        idle.image = new byte[0];
        idle.memBase = 0;
        idle.memSize = 0;
        idle.timeSliceLeft = Scheduler.TIME_SLICE;
    }

    public void start(long periodMillis) {
        this.pulseMillis = periodMillis;
        cpu.start(periodMillis);
    }

    private volatile long pulseMillis = 500;

    /** 调整"时间单位"的实际长度（毫秒）。 */
    public void setPulseMillis(long ms) {
        synchronized (lock) { setPulseMillisLocked(ms); }
    }
    private void setPulseMillisLocked(long ms) {
        long v = Math.max(20, ms);
        if (v == pulseMillis) {
            return;   // 值没变就不改，也不刷日志
        }
        pulseMillis = v;
        cpu.setPeriodMillis(v);   // 只改周期，不重启脉冲线程
        log("时间单位长度调整为 " + pulseMillis + " ms");
    }

    public long pulseMillis() {
        return pulseMillis;
    }

    public void shutdown() {
        cpu.stop();
    }

    // ==================================================================
    // CPU.Host 回调
    // ==================================================================

    @Override
    public int onArrive() {
        if (!autoArrivals || --arriveCountdown > 0) {
            return -1;
        }
        arriveCountdown = 2 + random.nextInt(5);
        List<String> candidates = executableFiles();
        if (candidates.isEmpty()) {
            return -1;
        }
        String path = candidates.get(random.nextInt(candidates.size()));
        if (scheduler.freeQueue().isEmpty()) {
            // PCB 已满：随机到达的进程无法创建。为避免日志被刷屏，同一次拥塞只记一次。
            if (!pcbFullNoted) {
                pcbFullNoted = true;
                log("新进程随机到达失败：PCB 已满（最多 " + Scheduler.MAX_PROCESS + " 个），等待有进程结束后再创建");
                results.addFirst("PCB 已满：随机到达的进程创建失败（等待回收）");
                while (results.size() > 12) {
                    results.removeLast();
                }
            }
            return -1;
        }
        pcbFullNoted = false;
        return create(path);
    }

    /** PCB 满日志去重标记。 */
    private boolean pcbFullNoted;

    @Override
    public void onTimeSliceExpired() {
        synchronized (lock) { onTimeSliceExpiredLocked(); }
    }
    private void onTimeSliceExpiredLocked() {
        PCB p = scheduler.running();
        if (p != null && p.pid != Scheduler.IDLE_PID) {
            log("时间片到：保存 P" + p.pid + " 现场(PC=" + p.pc + ", x=" + p.ax + ")，转入就绪队列");
            p.state = ProcessState.READY;
            scheduler.enqueueReady(p.pid);
        }
        scheduler.setRunningPid(-1);
        int next = onSchedule();
        log("调度：P" + next + " 获得处理器");
    }

    @Override
    public void onIoComplete(List<DeviceManager.Device> finishedDevices) {
        synchronized (lock) {
            for (DeviceManager.Device d : finishedDevices) {
                int owner = d.owner;
                int next = devices.release(d);
                PCB completed = scheduler.pcb(owner);
                if (completed != null) completed.waitingDevice = -1;
                awake(owner);
                log("I/O 中断：设备" + d.name() + "完成，唤醒P" + owner);
                noteDeviceHandoff(d, next);
            }
        }
    }
    private void noteDeviceHandoff(DeviceManager.Device d, int next) {
        if (next < 0) return;
        PCB waiting = scheduler.pcb(next);
        waiting.waitingDevice = d.index;
        log("设备" + d.name() + "分配给等待者P" + next + "，继续阻塞直至I/O完成");
    }

    @Override
    public void onProgramEnd(PCB p) {
        synchronized (lock) { onProgramEndLocked(p); }
    }
    private void onProgramEndLocked(PCB p) {
        if (p == null) {
            return;
        }
        log("程序结束中断：P" + p.pid + " 执行完 " + p.programPath + "，最终 x = " + p.ax);
        results.addFirst(String.format("P%d(%s) 执行结束，输出 x = %d", p.pid, p.name, p.ax));
        while (results.size() > 12) {
            results.removeLast();
        }
        // 调用进程撤销原语，然后进行进程调度
        destroy(p.pid);
    }

    @Override
    public void onIllegalInstruction(PCB p, int opcode) {
        log("非法指令：P" + p.pid + "遇到0x" + String.format("%02X", opcode));
    }
    @Override
    public void onProcessFault(PCB p, String reason) {
        synchronized (lock) { onProcessFaultLocked(p, reason); }
    }
    private void onProcessFaultLocked(PCB p, String reason) {
        log("异常结束：P" + p.pid + " " + reason);
        results.addFirst("P" + p.pid + "(" + p.name + ")异常结束：" + reason);
        while (results.size() > 12) results.removeLast();
        destroy(p.pid);
    }

    @Override
    public void onDeviceBlocked(PCB p, String deviceType, int duration) {
        DeviceManager.Device d = devices.deviceOf(p.pid);
        p.waitingDevice = d == null ? -1 : d.index;
        block(p.pid, BlockReason.DEVICE);
    }

    @Override
    public int onSchedule() {
        synchronized (lock) { return onScheduleLocked(); }
    }
    private int onScheduleLocked() {
        // 上一个进程不再占用处理器：把它从"运行"状态摘下来
        // （闲逛进程被抢占时必须改回就绪，否则界面会一直显示它在运行）
        PCB prev = scheduler.running();
        if (prev != null) {
            cpu.saveContext(prev);
            if (prev.pid == Scheduler.IDLE_PID) {
                prev.state = ProcessState.READY;
                prev.blockReason = BlockReason.IDLE;
            } else if (prev.state == ProcessState.RUNNING) {
                scheduler.enqueueReady(prev.pid);
            }
        }
        scheduler.setRunningPid(-1);

        Integer pid = scheduler.dequeueReady();
        while (pid != null && pid == Scheduler.IDLE_PID) {
            // 闲逛进程不参与就绪队列竞争，跳过（它在无进程可运行时被直接投运）
            pid = scheduler.dequeueReady();
        }
        if (pid == null) {
            // 就绪队列为空 → 运行闲逛进程
            PCB idle = scheduler.pcb(Scheduler.IDLE_PID);
            idle.state = ProcessState.RUNNING;
            idle.blockReason = BlockReason.IDLE;
            scheduler.setRunningPid(Scheduler.IDLE_PID);
            cpu.restoreContext(idle);
            return Scheduler.IDLE_PID;
        }
        return run(pid);
    }

    /** 恢复现场并投运某个进程。 */
    private int run(int pid) {
        PCB p = scheduler.pcb(pid);
        if (p == null) {
            return -1;
        }
        p.state = ProcessState.RUNNING;
        p.blockReason = BlockReason.NONE;
        p.timeSliceLeft = Scheduler.TIME_SLICE;
        scheduler.setRunningPid(pid);
        cpu.restoreContext(p);
        return pid;
    }

    /** 进程执行完的结果列表，界面"进程执行完，显示结果"一栏使用。 */
    private final Deque<String> results = new ArrayDeque<>();

    public List<String> results() {
        return new ArrayList<>(results);
    }

    @Override
    public void onIdlePreemptCheck() {
        preemptIdleIfNeeded();
    }

    @Override
    public void onClockTick() {
        systemClock++;
    }

    /**
     * 闲逛进程让位判定：CPU 正在运行闲逛进程、且已有可运行进程时，立即重新调度。
     * 指导书要求"当有进程就绪时，就调用就绪进程运行"，这里实现该抢占。
     */
    public void preemptIdleIfNeeded() {
        if (scheduler.runningPid() != Scheduler.IDLE_PID) {
            return;
        }
        for (PCB p : scheduler.pcbs()) {
            if (p.pid != Scheduler.IDLE_PID && p.state == ProcessState.READY) {
                log("有进程就绪，闲逛进程让出处理器，重新调度");
                PCB idle = scheduler.pcb(Scheduler.IDLE_PID);
                idle.state = ProcessState.READY;
                idle.blockReason = BlockReason.IDLE;
                scheduler.setRunningPid(-1);
                onSchedule();
                return;
            }
        }
    }

    // ==================================================================
    // 进程原语
    // ==================================================================

    /**
     * 进程创建原语。
     * <p>四步：申请空白 PCB → 申请主存空间（成功则装入主存）→ 初始化 PCB → 挂入就绪队列。</p>
     *
     * @return 新进程 pid；失败返回 -1
     */
    public int create(String programPath) {
        synchronized (lock) {
            FileEntry e = fs.resolve(programPath);
            if (e == null || !e.isExecutable()) {
                log("进程创建失败：需要.e可执行文件 " + programPath); return -1;
            }
            byte[] image = fs.readAll(e);
            if (image.length == 0 || image.length > MemoryManager.USER_AREA_SIZE) {
                log("进程创建失败：程序大小必须为1~512字节"); return -1;
            }
            PCB p = scheduler.allocatePcb();
            if (p == null) { log("进程创建失败：PCB已满"); return -1; }
            try {
                p.name = e.displayName();
                p.programPath = FileSystem.normalize(programPath);
                p.memSize = image.length;
                p.timeSliceLeft = Scheduler.TIME_SLICE;
                try {
                    MemoryManager.Partition part = memory.allocate(p.pid, image.length);
                    p.memBase = part.start;
                } catch (IllegalStateException noMemory) {
                    p.image = image; // 仅未装入的程序保留暂存副本。
                    p.blockedSince = systemClock;
                    scheduler.enqueueBlocked(p.pid, BlockReason.MEMORY);
                    log("P" + p.pid + "等待内存，需求" + image.length + "字节");
                    return p.pid;
                }
                memory.load(p.pid, image);
                p.image = new byte[0];
                scheduler.enqueueReady(p.pid);
                log("创建进程P" + p.pid + "（" + p.name + "，" + image.length + "字节），挂入就绪队列");
                return p.pid;
            } catch (RuntimeException failure) {
                memory.free(p.pid); scheduler.freePcb(p.pid); throw failure;
            }
        }
    }

    /**
     * 进程撤销原语。
     * <p>三步：回收进程所占内存 → 回收进程控制块 → 在屏幕上显示进程执行结果。</p>
     */
    public void destroy(int pid) {
        synchronized (lock) { destroyLocked(pid); }
    }
    private void destroyLocked(int pid) {
        if (pid == Scheduler.IDLE_PID) {
            return;
        }
        PCB p = scheduler.pcb(pid);
        if (p == null) {
            return;
        }
        memory.free(pid);
        devices.removeFromAllQueues(pid);
        DeviceManager.Device d = devices.deviceOf(pid);
        if (d != null) {
            int next = devices.release(d);
            noteDeviceHandoff(d, next);
        }
        log("撤销进程 P" + pid + "：回收内存并归还 PCB");
        boolean wasRunning = scheduler.runningPid() == pid;
        scheduler.freePcb(pid);
        retryMemoryWaiters();
        // 撤销正在运行的进程后必须重新调度
        if (wasRunning) {
            onSchedule();
        }
    }

    /**
     * 进程阻塞原语。
     * <p>三步：保存运行进程的 CPU 现场 → 修改进程状态 → 链入对应阻塞队列，然后转向进程调度。</p>
     */
    public void block(int pid, BlockReason reason) {
        synchronized (lock) { blockLocked(pid, reason); }
    }
    private void blockLocked(int pid, BlockReason reason) {
        PCB p = scheduler.pcb(pid);
        if (p == null || p.state == ProcessState.FREE) {
            return;
        }
        // 进程主动阻塞时，本次调度已经由本原语自己完成，清除"时间片到"中断位，
        // 避免进程被唤醒后立刻又触发一次多余的调度。
        p.psw = cn.edu.scau.os.cpu.InterruptPSW.clear(p.psw, cn.edu.scau.os.cpu.InterruptPSW.BIT_SCHEDULE);
        if (scheduler.runningPid() == pid) cpu.saveContext(p);
        // 保存现场后转入阻塞队列。
        p.state = ProcessState.BLOCKED;
        p.blockedSince = systemClock;
        p.blockReason = reason;
        scheduler.enqueueBlocked(pid, reason);
        if (scheduler.runningPid() == pid) {
            log("阻塞 P" + pid + "（" + reason.text() + "）：保存现场 PC=" + p.pc + " x=" + p.ax + "，转入阻塞队列");
            scheduler.setRunningPid(-1);
            onSchedule();
        } else {
            log("P" + pid + " 进入阻塞队列（" + reason.text() + "）");
        }
    }

    /**
     * 进程唤醒原语：把进程由阻塞队列中摘下，修改状态为就绪，然后链入就绪队列。
     */
    public void awake(int pid) {
        synchronized (lock) { awakeLocked(pid); }
    }
    private void awakeLocked(int pid) {
        PCB p = scheduler.pcb(pid);
        if (p == null || p.state != ProcessState.BLOCKED) {
            return;
        }
        scheduler.removeBlocked(pid);
        p.blockReason = BlockReason.NONE;
        scheduler.enqueueReady(pid);
        p.blockedSince = -1;
        log("唤醒 P" + pid + "：置为就绪并链入就绪队列");
    }

    /** 检查是否能把"等待内存"的进程装入内存（内存回收后调用）。 */
    public void retryMemoryWaiters() {
        synchronized (lock) { retryMemoryWaitersLocked(); }
    }
    private void retryMemoryWaitersLocked() {
        for (PCB p : scheduler.pcbs()) {
            if (p.state == ProcessState.BLOCKED && p.blockReason == BlockReason.MEMORY) {
                try {
                    MemoryManager.Partition part = memory.allocate(p.pid, p.memSize);
                    p.memBase = part.start;
                    memory.load(p.pid, p.image);
                    p.image = new byte[0];
                    p.pc = 0;
                    p.timeSliceLeft = Scheduler.TIME_SLICE;
                    awake(p.pid);
                    log("P" + p.pid + " 内存申请成功（" + p.memSize + " 字节），装入内存并唤醒");
                } catch (IllegalStateException ignore) {
                    // 内存仍不足，继续等待
                }
            }
        }
    }

    // ==================================================================
    // 文件系统辅助
    // ==================================================================

    /** 递归收集全部可执行文件（扩展名为 e）。 */
    public List<String> executableFiles() {
        synchronized (lock) {
            refreshFileCache();
            return new ArrayList<>(cachedExecutables);
        }
    }

    private void refreshFileCache() {
        if (cachedFsRevision == fs.revisions()) return;
        cachedRoot = fs.rootTree();
        List<String> paths = new ArrayList<>();
        collectCachedExecutables(cachedRoot, "", paths);
        cachedExecutables = List.copyOf(paths);
        cachedFsRevision = fs.revisions();
    }
    private void collectCachedExecutables(FileSystem.DirNode node, String prefix, List<String> paths) {
        for (FileSystem.DirNode child : node.children()) {
            String path = prefix + "/" + child.name();
            if (child.directory()) collectCachedExecutables(child, path, paths);
            else if (child.name().toLowerCase(java.util.Locale.ROOT).endsWith(".e")) paths.add(path);
        }
    }

    /** 把源文本汇编后写入可执行文件（界面"编译并保存"用）。 */
    public Assembler.Result assembleAndSave(String path, String source) {
        synchronized (lock) { return assembleAndSaveLocked(path, source); }
    }
    private Assembler.Result assembleAndSaveLocked(String path, String source) {
        path = FileSystem.normalize(path);
        if (!DiskLayout.EXT_EXECUTABLE.equalsIgnoreCase(FileSystem.extensionOf(path)))
            throw new IllegalArgumentException("汇编程序必须保存为.e文件");
        FileSystem.checkName(FileSystem.baseName(path), FileSystem.extensionOf(path));
        Assembler.Result r = Assembler.assemble(source);
        if (!r.ok()) {
            return r;
        }
        String dir = path.substring(0, path.lastIndexOf('/'));
        int parent = dir.isEmpty() ? DiskLayout.ROOT_BLOCK : fs.requireDir(dir);
        fs.writeFile(parent, FileSystem.baseName(path), FileSystem.extensionOf(path),
                DiskLayout.ATTR_NORMAL, r.code());
        log("汇编并保存 " + path + "（" + r.code().length + " 字节机器码）");
        return r;
    }

    // ==================================================================
    // 快照（界面读取）
    // ==================================================================

    /** 界面显示所需的全部状态快照。全部字段都是拷贝，可在锁外安全阅读。 */
    public record Snapshot(int tick,
                           int systemClock,
                           List<PcbView> pcbs,
                           List<Integer> readyQueue,
                           List<Integer> blockedQueue,
                           List<Integer> freeQueue,
                           List<MemoryManager.Partition> memoryPartitions,
                           int[] memoryOwners,
                           int memoryFree,
                           List<DevView> deviceViews,
                           List<DevWaitView> deviceWaitViews,
                           List<FileSystem.DirNode> rootChildren,
                           int[] fat,
                           int usedBlocks,
                           int freeBlocks,
                           List<String> logLines,
                           List<String> results,
                           String runningInfo,
                           String currentInstruction,
                           String lastEvent,
                           String dirtyNote) {
    }

    public record PcbView(int pid, String name, String state, String blockReason, int ax, int pc,
                          int timeSliceLeft, int memBase, int memSize, String programPath,
                          int psw, String device, int blockedSince) {
    }

    public record DevView(String name, String type, int index, int owner, String ownerText, int remaining) {
    }

    public record DevWaitView(String type, int total, int busy, List<Integer> waiting) {
    }

    private volatile String dirtyNote = "";

    public void setDirtyNote(String note) {
        this.dirtyNote = note;
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            List<PcbView> pcbs = new ArrayList<>();
            for (PCB p : scheduler.pcbs()) {
                DeviceManager.Device d = devices.deviceOf(p.pid);
                pcbs.add(new PcbView(p.pid, p.name, p.state.text(), p.blockReason.text(),
                        p.ax, p.pc, p.timeSliceLeft, p.memBase, p.memSize, p.programPath,
                        p.psw, d == null ? "—" : d.name(), p.blockedSince));
            }

            List<DevView> dv = new ArrayList<>();
            for (DeviceManager.Device d : devices.devices()) {
                dv.add(new DevView(d.name(), d.type, d.index, d.owner,
                        d.owner < 0 ? "空闲" : "P" + d.owner, d.remaining));
            }

            List<DevWaitView> dwv = new ArrayList<>();
            for (String type : List.of("A", "B", "C")) {
                List<DeviceManager.Device> dt = devices.devicesOfType(type);
                int busy = 0;
                for (DeviceManager.Device d : dt) {
                    if (!d.isFree()) {
                        busy++;
                    }
                }
                dwv.add(new DevWaitView(type, dt.size(), busy, devices.waitQueue(type)));
            }

            refreshFileCache();
            List<FileSystem.DirNode> tree = cachedRoot.children();

            int[] owners = memory.ownerMap();
            PCB run = scheduler.running();
            String runningInfo = run == null
                    ? "无（CPU 空闲）"
                    : String.format("P%d  %s  x=%d  PC=%d  片余=%d", run.pid, run.name, run.ax, run.pc, run.timeSliceLeft);

            int[] fatSnap = fs.fatSnapshot();
            int freeBlocks = 0;
            for (int i = DiskLayout.FIRST_DATA_BLOCK; i < fatSnap.length; i++) {
                if (fatSnap[i] == DiskLayout.FAT_FREE) {
                    freeBlocks++;
                }
            }

            return new Snapshot((int) cpu.tick(), systemClock, pcbs,
                    scheduler.readyQueue(), scheduler.blockedQueue(), scheduler.freeQueue(),
                    memory.partitions(), owners, memory.freeBytes(),
                    dv, dwv, tree, fatSnap, fs.usedBlocks(), freeBlocks,
                    logLines(120), results(),
                    runningInfo, cpu.lastInstruction(), cpu.lastEvent(), dirtyNote);
        }
    }

    // ==================================================================
    // 用户命令接口
    // ==================================================================

    /** 命令执行结果。 */
    public record CommandResult(boolean ok, String message) {
    }

    /**
     * 执行一条用户命令。指导书要求的必做命令：
     * create / delete / type / copy / mkdir / rmdir；
     * 可选命令：chdir / deldir / move / change / format / fdk。
     */
    public CommandResult exec(String commandLine) {
        String line = commandLine == null ? "" : commandLine.trim();
        if (line.isEmpty()) {
            return new CommandResult(true, "");
        }
        synchronized (lock) {
            String[] tok = line.split("\\s+");
            String cmd = tok[0].toLowerCase();
            try {
                switch (cmd) {
                    case "run": {
                        requireArgs(tok, 2, "run <可执行文件路径>");
                        int pid = create(FileSystem.normalize(tok[1]));
                        return new CommandResult(pid >= 0, pid < 0 ? "无法创建进程，请检查文件与PCB容量"
                                : "已创建进程P" + pid + "，状态：" + scheduler.pcb(pid).state.text());
                    }
                    case "create": {
                        requireArgs(tok, 2, "create <路径> [文本内容]");
                        String path = FileSystem.normalize(tok[1]);
                        // 内容可以是"文本"，也可以是汇编源码（写入 .e 文件时自动汇编）；
                        // 若某个参数以 asm: 开头，则其后的内容按汇编源码处理。
                        StringBuilder sb = new StringBuilder();
                        for (int i = 2; i < tok.length; i++) {
                            String t = tok[i];
                            if (t.startsWith("asm:")) {
                                t = t.substring(4);
                            }
                            sb.append(i > 2 ? " " : "").append(t);
                        }
                        String text = sb.toString();
                        if (DiskLayout.EXT_EXECUTABLE.equalsIgnoreCase(FileSystem.extensionOf(path))) text = text.replace("|", "\n");
                        byte[] content = text.isEmpty() ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
                        if (DiskLayout.EXT_EXECUTABLE.equalsIgnoreCase(FileSystem.extensionOf(path))) {
                            // .e 文件按源码汇编后写入
                            Assembler.Result r = Assembler.assemble(text);
                            if (!r.ok()) {
                                return new CommandResult(false, "汇编失败：" + String.join("；", r.errors()));
                            }
                            content = r.code();
                        }
                        fs.createFile(path, DiskLayout.ATTR_NORMAL, content);
                        log("create " + path + "（" + content.length + " 字节）");
                        return new CommandResult(true, "已创建 " + path);
                    }
                    case "delete": {
                        requireArgs(tok, 2, "delete <路径>");
                        String path = FileSystem.normalize(tok[1]);
                        fs.deleteFile(path);
                        retryMemoryWaiters();
                        log("delete " + path);
                        return new CommandResult(true, "已删除 " + path);
                    }
                    case "type": {
                        requireArgs(tok, 2, "type <路径>");
                        String path = FileSystem.normalize(tok[1]);
                        FileEntry e = fs.require(path);
                        if (e.isDirectory()) {
                            return new CommandResult(false, path + " 是目录，不能 type");
                        }
                        byte[] data = fs.readAll(e);
                        if (e.isExecutable()) {
                            return new CommandResult(true, "反汇编 " + path + "：\n" + Assembler.disassemble(data));
                        }
                        return new CommandResult(true, "内容 " + path + "：\n" + new String(data, StandardCharsets.UTF_8));
                    }
                    case "copy": {
                        requireArgs(tok, 3, "copy <源路径> <目标路径>");
                        String src = FileSystem.normalize(tok[1]);
                        String dst = FileSystem.normalize(tok[2]);
                        // copy 允许显式给出目标扩展名：copy /aa/ccc /dd/nnn e
                        if (tok.length >= 4 && !tok[3].isBlank()) {
                            dst = FileSystem.normalize(dst + "." + tok[3]);
                        }
                        fs.copyFile(src, dst);
                        log("copy " + src + " -> " + dst);
                        return new CommandResult(true, "已拷贝 " + src + " -> " + dst);
                    }
                    case "mkdir": {
                        requireArgs(tok, 2, "mkdir <路径>");
                        String path = FileSystem.normalize(tok[1]);
                        fs.createDirectory(path);
                        log("mkdir " + path);
                        return new CommandResult(true, "已建立目录 " + path);
                    }
                    case "rmdir": {
                        requireArgs(tok, 2, "rmdir <路径>");
                        String path = FileSystem.normalize(tok[1]);
                        fs.removeEmptyDirectory(path);
                        retryMemoryWaiters();
                        log("rmdir " + path);
                        return new CommandResult(true, "已删除空目录 " + path);
                    }
                    case "deldir": {
                        requireArgs(tok, 2, "deldir <路径>");
                        String path = FileSystem.normalize(tok[1]);
                        fs.removeDirectoryRecursively(path);
                        log("deldir " + path);
                        return new CommandResult(true, "已删除目录（含内容）" + path);
                    }
                    case "change": {
                        requireArgs(tok, 3, "change <路径> <属性: 普通|只读|系统>");
                        String path = FileSystem.normalize(tok[1]);
                        int attr = switch (tok[2]) {
                            case "只读", "readonly", "ro" -> DiskLayout.ATTR_READONLY;
                            case "系统", "system" -> DiskLayout.ATTR_SYSTEM;
                            case "普通", "normal" -> DiskLayout.ATTR_NORMAL;
                            default -> throw new IllegalArgumentException("未知属性：" + tok[2]);
                        };
                        fs.changeAttributes(path, attr);
                        return new CommandResult(true, "已修改属性 " + path);
                    }
                    case "dir", "ls": {
                        StringBuilder sb = new StringBuilder();
                        renderTree(fs.rootTree(), 0, sb);
                        return new CommandResult(true, sb.toString());
                    }
                    case "format": {
                        initialize((int) System.currentTimeMillis());
                        return new CommandResult(true, "磁盘已格式化（注意：演示数据已重建）");
                    }
                    case "help": {
                        return new CommandResult(true, helpText());
                    }
                    default:
                        return new CommandResult(false, "未知命令：" + cmd + "\n" + helpText());
                }
            } catch (RuntimeException e) {
                log("命令失败：" + line + " —— " + e.getMessage());
                return new CommandResult(false, "执行失败：" + e.getMessage());
            }
        }
    }

    private static void requireArgs(String[] tok, int n, String usage) {
        if (tok.length < n) {
            throw new IllegalArgumentException("参数不足，用法：" + usage);
        }
    }

    private void renderTree(FileSystem.DirNode node, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth)).append(node.directory() ? "[D] " : "    ")
                .append(node.name()).append(node.directory() ? "" : "  (" + node.length() + "B, 起始盘块 " + node.startBlock() + ")")
                .append('\n');
        for (FileSystem.DirNode c : node.children()) {
            renderTree(c, depth + 1, sb);
        }
    }

    public static String helpText() {
        return """
                必做命令（注意：目录项名字段只有 3 字节、扩展名只有 1 字节）：
                  create <路径> [内容]        建立文件；写 .e 文件时内容按汇编源码编译
                  delete <路径>               删除文件
                  type   <路径>               显示文件内容（.e 文件显示反汇编结果）
                  copy   <源> <目标> [扩展名]  拷贝文件，可显式指定目标扩展名
                  mkdir  <路径>               建立目录（无扩展名）
                  rmdir  <路径>               删除空目录（非空报错）
                可选命令：
                  run <路径>                  创建进程运行.e文件
                  deldir <路径>               删除目录（含内容）
                  change <路径> <属性>        修改属性（普通/只读/系统）
                  dir                         显示目录树
                  format                      格式化磁盘并重建演示数据
                  help                        显示本帮助
                示例：
                  create /aa/bb.e x=1|x++|!A3|end    （用 | 分隔汇编源码的每一行）
                  copy /aa/ccc /dd/nnn e             （目标扩展名 e）
                  mkdir /dd
                提示：文件名/目录名最长 3 个字符，只允许字母和数字；路径用 / 或 \\ 分隔。
                """;
    }
}
