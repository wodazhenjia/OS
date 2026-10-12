package cn.edu.scau.os.cpu;

import cn.edu.scau.os.device.DeviceManager;
import cn.edu.scau.os.disk.FileSystem;
import cn.edu.scau.os.instruction.Instruction;
import cn.edu.scau.os.instruction.Opcode;
import cn.edu.scau.os.process.PCB;
import cn.edu.scau.os.process.Scheduler;
import cn.edu.scau.os.storage.MemoryManager;
import java.util.List;

/** 单CPU、独立寄存器、统一中断入口。每个脉冲最多执行一条原子指令。 */
public class CPU {
    public record CycleResult(int tick, int runningPid, String instruction, String event) { }
    public interface Host {
        int onArrive();
        void onTimeSliceExpired();
        void onIoComplete(List<DeviceManager.Device> finishedDevices);
        void onProgramEnd(PCB p);
        void onIllegalInstruction(PCB p, int opcode);
        void onProcessFault(PCB p, String reason);
        void onDeviceBlocked(PCB p, String deviceType, int duration);
        int onSchedule();
        void onIdlePreemptCheck();
        void onClockTick();
        void log(String message);
    }
    private final Scheduler scheduler;
    private final MemoryManager memory;
    private final DeviceManager devices;
    private final Host host;
    private final Object lock;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile long periodMillis = 500;
    private volatile Thread thread;
    private long tick;
    private int ax, pc, ir, psw, sliceLeft;
    private String fault;
    private volatile String lastInstruction = "(尚未开始)";
    private volatile String lastEvent = "(等待启动)";

    public CPU(Scheduler scheduler, MemoryManager memory, DeviceManager devices, FileSystem fs, Host host, Object lock) {
        this.scheduler = scheduler; this.memory = memory; this.devices = devices; this.host = host; this.lock = lock;
    }
    public long tick() { return tick; }
    public String lastInstruction() { return lastInstruction; }
    public String lastEvent() { return lastEvent; }
    public boolean isRunning() { return running; }
    public boolean isPaused() { return paused; }
    public void setPaused(boolean value) { synchronized (lock) { paused = value; } }
    public long periodMillis() { return periodMillis; }
    public void setPeriodMillis(long ms) { periodMillis = Math.max(20, ms); }

    public synchronized void start(long period) {
        if (thread != null && thread.isAlive()) return;
        setPeriodMillis(period);
        running = true;
        thread = new Thread(this::CPU, "sim-cpu");
        thread.setDaemon(true);
        thread.start();
    }
    public synchronized void stop() {
        running = false;
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (t.isAlive()) throw new IllegalStateException("CPU线程尚未停止");
        }
        thread = null;
    }
    /** 无参CPU入口；周期在循环中读取，调整速度不会重建线程。 */
    public void CPU() {
        while (running && Thread.currentThread() == thread) {
            synchronized (lock) {
                if (!paused && running) {
                    try { cycle(); }
                    catch (RuntimeException e) { paused = true; host.log("[模拟暂停] " + e.getMessage()); }
                }
            }
            try { Thread.sleep(paused ? 60 : periodMillis); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
    public void reset() {
        tick = 0; ax = pc = ir = psw = sliceLeft = 0; fault = null;
        lastInstruction = "(尚未开始)"; lastEvent = "(等待启动)";
    }
    public void saveContext(PCB p) {
        if (p == null) return;
        p.ax = ax; p.pc = pc; p.ir = ir; p.psw = psw; p.timeSliceLeft = sliceLeft;
    }
    public void restoreContext(PCB p) {
        ax = p.ax; pc = p.pc; ir = p.ir; psw = p.psw; sliceLeft = p.timeSliceLeft; fault = null;
    }
    public CycleResult step() {
        synchronized (lock) {
            paused = true;
            return cycle();
        }
    }
    public CycleResult cycle() {
        synchronized (lock) {
            // 只推进本单位开始前已有的I/O；本单位新申请的设备从下一单位开始计时。
            List<DeviceManager.Device> finished = devices.tick();
            if (!finished.isEmpty()) psw |= InterruptPSW.BIT_IO;
            handleInterrupts(finished);
            host.onArrive();
            host.onIdlePreemptCheck();
            if (scheduler.running() == null) host.onSchedule();
            PCB p = scheduler.running();
            if (p == null || p.pid == Scheduler.IDLE_PID) {
                lastInstruction = "(闲逛)"; lastEvent = "就绪队列为空，CPU运行闲逛进程";
            } else {
                sliceLeft--; // 仅向本次真正执行的进程计费；阻塞后新投运进程保留完整时间片。
                try { executeOne(p); }
                catch (RuntimeException e) {
                    fault = e.getMessage();
                    psw |= InterruptPSW.BIT_END;
                    lastEvent = "进程异常：" + fault;
                }
                if (scheduler.running() == p) {
                    if (sliceLeft == 0 && (psw & InterruptPSW.BIT_END) == 0) psw |= InterruptPSW.BIT_SCHEDULE;
                    saveContext(p);
                }
            }
            tick++;
            host.onClockTick();
            return new CycleResult((int) tick, scheduler.runningPid(), lastInstruction, lastEvent);
        }
    }
    /** 在下一条指令之前集中处理I/O、结束和时钟中断；结束优先于同进程时间片中断。 */
    private void handleInterrupts(List<DeviceManager.Device> finished) {
        if ((psw & InterruptPSW.BIT_IO) != 0) {
            psw &= ~InterruptPSW.BIT_IO;
            host.onIoComplete(finished);
        }
        PCB p = scheduler.running();
        if (p == null) return;
        if ((psw & InterruptPSW.BIT_END) != 0) {
            psw &= ~(InterruptPSW.BIT_END | InterruptPSW.BIT_SCHEDULE);
            saveContext(p);
            String reason = fault;
            fault = null;
            if (reason == null) host.onProgramEnd(p); else host.onProcessFault(p, reason);
        } else if ((psw & InterruptPSW.BIT_SCHEDULE) != 0) {
            psw &= ~InterruptPSW.BIT_SCHEDULE;
            saveContext(p);
            host.onTimeSliceExpired();
        }
    }
    private void executeOne(PCB p) {
        // 基址、限长和实际分区必须一致，PCB故障不能绕开保护。
        var partition = memory.partitionOf(p.pid);
        if (partition == null || partition.start != p.memBase || partition.size != p.memSize
                || !memory.checkAccess(p.pid, p.memBase + pc, 1))
            throw new IllegalStateException("存储保护：P" + p.pid + "取指越界，PC=" + pc);
        ir = memory.readByte(p.pid, pc);
        Instruction ins = new Instruction(pc, ir, Opcode.operand(ir), 1, Opcode.mnemonic(ir));
        lastInstruction = "P" + p.pid + " " + String.format("%04X: %s", pc, ins.text());
        if (!ins.isLegal()) {
            host.onIllegalInstruction(p, ir);
            throw new IllegalStateException("非法指令0x" + String.format("%02X", ir));
        }
        pc++;
        if (ins.isAssign()) { ax = ins.operand(); lastEvent = "执行x=" + ax; }
        else if (ir == Opcode.OP_INC) { ax = Math.min(255, ax + 1); lastEvent = "执行x++ → x=" + ax; }
        else if (ir == Opcode.OP_DEC) { ax = Math.max(0, ax - 1); lastEvent = "执行x-- → x=" + ax; }
        else if (ins.isEnd()) {
            psw |= InterruptPSW.BIT_END;
            lastEvent = "程序结束中断待处理";
        } else if (ins.isDevice()) {
            if (ins.operand() == 0) { lastEvent = "零时间I/O立即完成"; return; }
            DeviceManager.Device d = devices.assign(ins.deviceName(), p.pid, ins.operand());
            if (d == null) devices.enqueueWait(ins.deviceName(), p.pid, ins.operand());
            psw &= ~InterruptPSW.BIT_SCHEDULE;
            saveContext(p);
            lastEvent = d == null ? "P" + p.pid + "等待设备" + ins.deviceName()
                    : "P" + p.pid + "使用设备" + d.name();
            host.onDeviceBlocked(p, ins.deviceName(), ins.operand());
        }
    }
}
