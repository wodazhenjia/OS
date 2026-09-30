package cn.edu.scau.os.cpu;

import cn.edu.scau.os.device.DeviceManager;
import cn.edu.scau.os.disk.FileSystem;
import cn.edu.scau.os.instruction.Assembler;
import cn.edu.scau.os.instruction.Instruction;
import cn.edu.scau.os.instruction.Opcode;
import cn.edu.scau.os.process.BlockReason;
import cn.edu.scau.os.process.PCB;
import cn.edu.scau.os.process.ProcessState;
import cn.edu.scau.os.process.Scheduler;
import cn.edu.scau.os.storage.MemoryManager;

import java.util.List;

/**
 * 中央处理器的模拟。
 *
 * <p>指导书原文："用函数 CPU()（该函数没有参数）模拟单个中央处理器……该函数主要负责解释
 * '可执行文件'中的指令……设一个'程序计数器'，跟踪现在执行到哪条指令……在 CPU() 函数中，
 * 每执行一条指令之前，先检查 PSW，判断有无中断，若有则先进行中断处理，然后再解释运行指令。
 * CPU 函数应该不断循环执行。"</p>
 *
 * <p>因此本类的入口方法就是无参的 {@link #CPU()}，内部为 {@code while(running)} 循环：
 * 每个循环 = 一个时间单位 = 至多执行一条指令。</p>
 *
 * <h2>与"真正并发线程"的关系</h2>
 * <p>指导书提示"可能需要多线程编程，才能保证这几种任务的并发"。本原型用
 * "单线程心跳 + 全局模拟锁"实现同等的并发语义：心跳每个时间单位推进一次，
 * 依次完成 ①随机新进程诞生 ②CPU 执行一条指令 ③设备倒计时 ④时钟递增/时间片递减。
 * 这样既满足"CPU 不断循环执行"的要求，又避免了多个真线程互相抢占导致模拟结果不确定
 * （演示时要求可复现，这一点很重要）。</p>
 */
public class CPU {

    /** 一次脉冲击活的结果，供界面与日志使用。 */
    public record CycleResult(int tick, int runningPid, String instruction, String event) {
    }

    /** 内核提供的回调集合，避免 CPU 与 Kernel 循环依赖。 */
    public interface Host {
        /** 本时间单位是否有新进程要诞生；返回新进程 pid，没有返回 -1。 */
        int onArrive();

        /** 相对时钟归零：把当前进程现场存入 PCB 并触发调度。 */
        void onTimeSliceExpired();

        /** I/O 中断：设备使用结束。 */
        void onIoComplete(List<DeviceManager.Device> finishedDevices);

        /** 程序结束中断（end）：输出 x 并撤销进程。 */
        void onProgramEnd(PCB p);

        /** 一条非法指令。 */
        void onIllegalInstruction(PCB p, int opcode);

        /** 进程因申请设备而阻塞。 */
        void onDeviceBlocked(PCB p, String deviceType, int duration);

        /** 每次调度时调用，选择下一个运行进程（返回 -1 表示无进程可运行）。 */
        int onSchedule();

        /** 闲逛进程让位检查：CPU 在跑闲逛进程而已有进程就绪时，应重新调度。 */
        void onIdlePreemptCheck();

        /** 每个时间单位调用一次：系统时钟递增 1（指导书：系统时钟用来记录开机以后的单位时间）。 */
        void onClockTick();

        /** 记录一条运行日志。 */
        void log(String message);
    }

    private final Scheduler scheduler;
    private final MemoryManager memory;
    private final DeviceManager devices;
    private final FileSystem fs;
    private final Host host;

    private volatile boolean running;
    private Thread thread;
    private long tick;
    private final Object lock;

    /** 最近一次执行结果的快照，界面读取用。 */
    private volatile String lastInstruction = "(尚未开始)";
    private volatile String lastEvent = "(等待启动)";

    public CPU(Scheduler scheduler, MemoryManager memory, DeviceManager devices, FileSystem fs,
               Host host, Object lock) {
        this.scheduler = scheduler;
        this.memory = memory;
        this.devices = devices;
        this.fs = fs;
        this.host = host;
        this.lock = lock;
    }

    public long tick() {
        return tick;
    }

    public String lastInstruction() {
        return lastInstruction;
    }

    public String lastEvent() {
        return lastEvent;
    }

    public boolean isRunning() {
        return running;
    }

    /** 启动模拟：CPU() 在不断循环执行。 */
    public void start(long periodMillis) {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(() -> CPU(periodMillis), "sim-cpu");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    /** 暂停/继续（暂停期间线程仍在循环，但不推进时间）。 */
    private volatile boolean paused;

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean p) {
        this.paused = p;
    }

    /**
     * 单个中央处理器的模拟 —— 无参入口，不断循环执行。
     */
    public void CPU(long periodMillis) {
        while (running) {
            if (paused) {
                sleep(60);
                continue;
            }
            synchronized (lock) {
                try {
                    cycle();
                } catch (RuntimeException e) {
                    host.log("[模拟异常] " + e);
                    lastEvent = "异常：" + e.getMessage();
                }
            }
            sleep(periodMillis);
        }
    }

    /** 推进一个时间单位。调用者必须已持有模拟锁。 */
    public CycleResult cycle() {
        tick++;

        // ① 随机新进程诞生（指导书：经过随机时间后，再选择一个可执行文件创建进程）
        host.onArrive();

        // ①' 若有进程已就绪而 CPU 正在跑闲逛进程，立即抢占（指导书：有进程就绪就调用它运行）
        host.onIdlePreemptCheck();

        // ② 每执行一条指令之前先检查 PSW，有中断先处理
        boolean interrupted = false;
        PCB p = scheduler.running();
        if (p != null && InterruptPSW.anyPending(p.psw)) {
            interrupted = true;
            handleInterrupts(p);
        }

        // ③ 解释执行一条指令
        p = scheduler.running();
        if (p != null) {
            executeOne(p);
        } else {
            // 没有进程在运行（例如刚被撤销）：触发一次调度
            int next = host.onSchedule();
            if (next >= 0) {
                lastEvent = "调度：选中 P" + next;
            } else {
                lastInstruction = "(空闲)";
                lastEvent = "无就绪进程，CPU 空转";
            }
        }

        // ④ 设备倒计时推进一个时间单位
        List<DeviceManager.Device> finished = devices.tick();
        if (!finished.isEmpty()) {
            // I/O 中断：先把 PSW 的 IO 位置 1，再进入中断处理
            PCB cur = scheduler.running();
            if (cur != null) {
                cur.psw = InterruptPSW.set(cur.psw, InterruptPSW.BIT_IO);
            }
            host.onIoComplete(finished);
            PCB after = scheduler.running();
            if (after != null) {
                after.psw = InterruptPSW.clear(after.psw, InterruptPSW.BIT_IO);
            }
        }

        // ⑤ 系统时钟递增、相对时钟（时间片）递减
        tickClock();

        return new CycleResult((int) tick, scheduler.runningPid(), lastInstruction, lastEvent);
    }

    /** 指令执行前的中断处理。 */
    private void handleInterrupts(PCB p) {
        if (InterruptPSW.isSet(p.psw, InterruptPSW.BIT_IO)) {
            p.psw = InterruptPSW.clear(p.psw, InterruptPSW.BIT_IO);
            lastEvent = "中断处理：I/O 完成";
        }
        if (InterruptPSW.isSet(p.psw, InterruptPSW.BIT_SCHEDULE)) {
            p.psw = InterruptPSW.clear(p.psw, InterruptPSW.BIT_SCHEDULE);
            lastEvent = "中断处理：时间片到，保存现场后调度";
            host.onTimeSliceExpired();
        }
        if (InterruptPSW.isSet(p.psw, InterruptPSW.BIT_END)) {
            p.psw = InterruptPSW.clear(p.psw, InterruptPSW.BIT_END);
            lastEvent = "中断处理：程序结束";
            host.onProgramEnd(p);
        }
    }

    /** 解释执行一条指令。 */
    private void executeOne(PCB p) {
        // 闲逛进程：什么有用的事也不做，只起"系统能正常运转"的作用，永不撤销
        if (p.pid == Scheduler.IDLE_PID) {
            lastInstruction = "(闲逛)";
            lastEvent = "就绪队列为空，CPU 运行闲逛进程";
            return;
        }
        if (p.image == null || p.image.length == 0) {
            lastInstruction = "(空程序)";
            host.onProgramEnd(p);
            return;
        }
        if (p.pc >= p.image.length) {
            // 文件里没有显式 end：按指导书语义在文件末尾结束进程
            p.psw = InterruptPSW.set(p.psw, InterruptPSW.BIT_END);
            lastInstruction = "end(文件末尾)";
            host.onProgramEnd(p);
            return;
        }

        Instruction ins = Assembler.decode(p.image, p.pc);
        p.ir = ins.opcode();
        lastInstruction = String.format("%04X: %s", ins.offset(), ins.text());

        if (!ins.isLegal()) {
            host.onIllegalInstruction(p, ins.opcode());
            host.onProgramEnd(p);
            return;
        }

        if (ins.isAssign()) {
            p.ax = ins.operand();
            p.pc += ins.length();
            lastEvent = "执行 x=" + p.ax;
        } else if (ins.opcode() == Opcode.OP_INC) {
            if (p.ax < 255) {
                p.ax++;
            } else {
                host.log("P" + p.pid + " x++ 溢出（x 上界 255），已忽略");
            }
            p.pc += ins.length();
            lastEvent = "执行 x++ → x=" + p.ax;
        } else if (ins.opcode() == Opcode.OP_DEC) {
            if (p.ax > 0) {
                p.ax--;
            } else {
                host.log("P" + p.pid + " x-- 下溢（x 下界 0），已忽略");
            }
            p.pc += ins.length();
            lastEvent = "执行 x-- → x=" + p.ax;
        } else if (ins.isDevice()) {
            String type = ins.deviceName();
            int duration = ins.operand();
            DeviceManager.Device d = devices.assign(type, p.pid, duration);
            if (d == null) {
                // 设备忙：进程进入该设备的等待队列并阻塞。
                // PC 保持指向本指令，被唤醒后重新执行"申请→分配→阻塞"这一整段。
                lastEvent = "P" + p.pid + " 申请 " + type + " 设备失败，进入等待队列";
            } else {
                // 申请成功：先推进 PC（本指令的分配动作已完成），再阻塞等待设备倒计时结束
                p.pc += ins.length();
                lastEvent = "P" + p.pid + " 分到设备 " + d.name() + "，倒计时 " + duration + " 后唤醒";
            }
            // 无论申请成功与否，本进程都挂在"该设备类型的等待队列"上，
            // 这样设备倒计时归零时（I/O 中断）才能按队列顺序把进程唤醒。
            // 这与指导书"分配设备后进程阻塞……设备使用倒计时至 0 后释放设备并唤醒进程"一致。
            devices.enqueueWait(type, p.pid);
            host.onDeviceBlocked(p, type, duration);
        } else if (ins.isEnd()) {
            p.psw = InterruptPSW.set(p.psw, InterruptPSW.BIT_END);
            lastInstruction = String.format("%04X: end", ins.offset());
            host.onProgramEnd(p);
        }
    }

    /** 系统时钟递增 1，相对时钟递减 1；归零则置"时间片到"中断位。 */
    private void tickClock() {
        host.onClockTick();
        PCB p = scheduler.running();
        if (p == null || p.state != ProcessState.RUNNING) {
            return;
        }
        if (p.pid == Scheduler.IDLE_PID) {
            return; // 闲逛进程不占用时间片
        }
        p.timeSliceLeft--;
        if (p.timeSliceLeft <= 0) {
            p.timeSliceLeft = 0;
            p.psw = InterruptPSW.set(p.psw, InterruptPSW.BIT_SCHEDULE);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(Math.max(1, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
