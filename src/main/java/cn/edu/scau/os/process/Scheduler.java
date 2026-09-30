package cn.edu.scau.os.process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 进程调度：三队列（空白 PCB 队列 / 就绪队列 / 阻塞队列）+ 时间片轮转。
 *
 * <p>指导书要点：</p>
 * <ul>
 *   <li>PCB 区最多 10 个，用数组模拟；根据内容不同组成三个队列；</li>
 *   <li>正在运行的进程只有一个；<b>系统初始时只有空白 PCB 队列</b>；</li>
 *   <li>采用时间片轮转调度算法，<b>时间片长度为 6</b>；</li>
 *   <li>调度函数要做三件事：保存现场 → 从就绪队列选一个进程 → 恢复现场并按 PC 继续执行；</li>
 *   <li>就绪队列为空时运行<b>闲逛进程</b>，有进程就绪时立即让位。</li>
 * </ul>
 */
public class Scheduler {

    /** 系统允许的进程控制块个数。 */
    public static final int MAX_PROCESS = 10;
    /** 时间片长度（时间单位）。 */
    public static final int TIME_SLICE = 6;
    /** 闲逛进程的 pid。 */
    public static final int IDLE_PID = 0;

    private final PCB[] pcbs = new PCB[MAX_PROCESS];
    private final Deque<Integer> readyQueue = new ArrayDeque<>();
    private final List<Integer> blockedQueue = new ArrayList<>();
    private int runningPid = -1;

    public Scheduler() {
        // 系统初始时只有空白 PCB 队列：全部 PCB 处于 FREE 状态
        for (int i = 0; i < MAX_PROCESS; i++) {
            PCB p = new PCB();
            p.pid = i;
            p.reset();
            p.pid = i;
            p.state = ProcessState.FREE;
            pcbs[i] = p;
        }
    }

    public PCB[] pcbs() {
        return pcbs;
    }

    public PCB pcb(int pid) {
        if (pid < 0 || pid >= MAX_PROCESS) {
            return null;
        }
        return pcbs[pid];
    }

    public PCB running() {
        return runningPid < 0 ? null : pcbs[runningPid];
    }

    public int runningPid() {
        return runningPid;
    }

    public void setRunningPid(int pid) {
        this.runningPid = pid;
    }

    public List<Integer> readyQueue() {
        return new ArrayList<>(readyQueue);
    }

    public List<Integer> blockedQueue() {
        return new ArrayList<>(blockedQueue);
    }

    /** 空白 PCB 队列（处于 FREE 状态的 pid 列表）。 */
    public List<Integer> freeQueue() {
        List<Integer> out = new ArrayList<>();
        for (PCB p : pcbs) {
            if (p.state == ProcessState.FREE) {
                out.add(p.pid);
            }
        }
        return out;
    }

    /** 申请一个空白 PCB，成功返回该 PCB，失败（已满）返回 null。 */
    public PCB allocatePcb() {
        for (PCB p : pcbs) {
            if (p.state == ProcessState.FREE) {
                int keep = p.pid;
                p.reset();
                // reset() 会把 pid 清成 -1，必须还原（PCB 的 pid 即数组下标，永不变化）
                p.pid = keep;
                p.state = ProcessState.READY;
                p.blockReason = BlockReason.NONE;
                p.timeSliceLeft = TIME_SLICE;
                return p;
            }
        }
        return null;
    }

    /** 回收 PCB（进程撤销时调用）。 */
    public void freePcb(int pid) {
        readyQueue.remove(pid);
        blockedQueue.remove((Integer) pid);
        PCB p = pcb(pid);
        if (p != null) {
            p.reset();
            p.pid = pid;
            p.state = ProcessState.FREE;
        }
        if (runningPid == pid) {
            runningPid = -1;
        }
    }

    /** 挂入就绪队列（队尾），对应指导书"从队尾挂入一个进程控制块"。 */
    public void enqueueReady(int pid) {
        PCB p = pcb(pid);
        if (p == null) {
            return;
        }
        p.state = ProcessState.READY;
        p.blockReason = BlockReason.NONE;
        if (!readyQueue.contains(pid)) {
            readyQueue.addLast(pid);
        }
    }

    /** 从就绪队列队头摘下一个进程。 */
    public Integer dequeueReady() {
        return readyQueue.pollFirst();
    }

    /** 挂入阻塞队列。 */
    public void enqueueBlocked(int pid, BlockReason reason) {
        PCB p = pcb(pid);
        if (p == null) {
            return;
        }
        p.state = ProcessState.BLOCKED;
        p.blockReason = reason;
        readyQueue.remove(pid);
        if (!blockedQueue.contains(pid)) {
            blockedQueue.add(pid);
        }
    }

    /** 从阻塞队列摘下（唤醒）。 */
    public void removeBlocked(int pid) {
        blockedQueue.remove((Integer) pid);
    }

    public boolean isReady(int pid) {
        return readyQueue.contains(pid);
    }

    public boolean isBlocked(int pid) {
        return blockedQueue.contains(pid);
    }
}
