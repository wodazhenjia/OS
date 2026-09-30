package cn.edu.scau.os.process;

/**
 * 进程控制块 PCB。
 *
 * <p>指导书要求 PCB 内容包括进程标识符、主要寄存器、进程状态、阻塞原因等，
 * 并明确"本模拟系统最多容纳 10 个进程控制块"，PCB 区用数组模拟。</p>
 *
 * <p>寄存器部分按指导书"用全局变量或数组模拟重要寄存器"的提示，直接放在 PCB 里，
 * 现场保存/恢复即为这些字段的拷贝。</p>
 */
public class PCB {

    // ---- 标识信息 ----
    /** 进程标识符。 */
    public int pid;
    /** 进程名（取自可执行文件名，便于对照）。 */
    public String name = "";

    // ---- 说明信息 ----
    public ProcessState state = ProcessState.FREE;
    public BlockReason blockReason = BlockReason.NONE;

    // ---- 现场信息（主要寄存器） ----
    /** 数据寄存器，存放 x 的值。 */
    public int ax;
    /** 程序状态寄存器：低 3 位为中断标志，见 InterruptPSW。 */
    public int psw;
    /** 指令寄存器：当前指令首字节。 */
    public int ir;
    /** 程序计数器：下一条待执行指令在可执行文件中的字节偏移。 */
    public int pc;
    /** 相对时钟：本进程剩余时间片。 */
    public int timeSliceLeft;

    // ---- 管理信息 ----
    /** 进程内存基址（用户区偏移）。 */
    public int memBase;
    /** 进程占用内存字节数。 */
    public int memSize;
    /** 进程映像（可执行文件字节码装入"内存"后的副本）。 */
    public byte[] image = new byte[0];
    /** 可执行文件路径，便于界面显示与撤销时释放。 */
    public String programPath = "";
    /** 等待设备号，-1 表示没有等待设备。 */
    public int waitingDevice = -1;
    /** 距离新进程诞生还剩多少个时间单位（由 create 调度用）。 */
    public int arriveCountdown;

    public void reset() {
        pid = -1;
        name = "";
        state = ProcessState.FREE;
        blockReason = BlockReason.NONE;
        ax = 0;
        psw = 0;
        ir = 0;
        pc = 0;
        timeSliceLeft = 0;
        memBase = 0;
        memSize = 0;
        image = new byte[0];
        programPath = "";
        waitingDevice = -1;
        arriveCountdown = 0;
    }

    /** 是否是可执行文件装入的"可运行"进程（闲逛进程与等待内存的进程另行判断）。 */
    public boolean runnable() {
        return state == ProcessState.RUNNING || state == ProcessState.READY;
    }

    @Override
    public String toString() {
        return String.format("PCB(P%d %s %s x=%d PC=%d 片=%d %s)",
                pid, name, state.text(), ax, pc, timeSliceLeft, blockReason.text());
    }
}
