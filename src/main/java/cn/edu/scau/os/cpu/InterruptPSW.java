package cn.edu.scau.os.cpu;

/**
 * 程序状态寄存器 PSW 的中断标志模拟。
 *
 * <p>指导书原文："发生下述 3 种模拟中断时，分别将 PSW 中的 3 个 bit 设置为 1，
 * 处理完中断后将相应 bit 设置为 0。"三种中断分别是：</p>
 * <ol>
 *   <li>{@link #BIT_END} 程序结束（执行 end 形成的软中断）；</li>
 *   <li>{@link #BIT_SCHEDULE} 时间片结束（相对时钟减到 0）；</li>
 *   <li>{@link #BIT_IO} I/O 中断（设备使用时间倒计时至 0）。</li>
 * </ol>
 */
public final class InterruptPSW {

    private InterruptPSW() {
    }

    /** bit0：程序结束中断。 */
    public static final int BIT_END = 0x01;
    /** bit1：时间片到，需要进程调度。 */
    public static final int BIT_SCHEDULE = 0x02;
    /** bit2：I/O 完成中断。 */
    public static final int BIT_IO = 0x04;

    public static boolean isSet(int psw, int bit) {
        return (psw & bit) != 0;
    }

    public static int set(int psw, int bit) {
        return psw | bit;
    }

    public static int clear(int psw, int bit) {
        return psw & ~bit;
    }

    public static boolean anyPending(int psw) {
        return (psw & (BIT_END | BIT_SCHEDULE | BIT_IO)) != 0;
    }

    /** 把 PSW 的低 3 位渲染成可读文本，供界面显示。 */
    public static String describe(int psw) {
        return String.format("%s%s%s",
                isSet(psw, BIT_IO) ? "IO" : "--",
                isSet(psw, BIT_SCHEDULE) ? "/片到" : "/----",
                isSet(psw, BIT_END) ? "/结束" : "/----");
    }
}
