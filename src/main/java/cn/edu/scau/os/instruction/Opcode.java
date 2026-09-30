package cn.edu.scau.os.instruction;

/**
 * 题目六要求的 5 种指令 + 1 字节编码方案。
 *
 * <p>指导书硬性要求：可执行文件中的"指令"只有 5 种，且<b>每条指令在文件中占 1 字节</b>。
 * 本类固化下面这张编码表（全项目共用：汇编器、反汇编显示、CPU 译码三处必须一致）。</p>
 *
 * <pre>
 * 位平面： bit7 bit6 | bit5 bit4 bit3 bit2 bit1 bit0
 *           └─ 操作码 ─┘└──────── 操作数 / 立即数 ────────┘
 *
 * 编码      二进制        助记符           语义
 * 0x00-0x3F 00 xxxxxx     x=?              x ← 操作数(0~63)，即"赋值"
 * 0x40      01 000000     x++              x ← x + 1（x 上界 255）
 * 0x41      01 000001     x--              x ← x − 1（x 下界 0）
 * 0x42      01 000010     !A?              申请 A 设备，紧跟 1 字节为使用时间 0~63
 * 0x43      01 000011     !B?              申请 B 设备，同上
 * 0x44      01 000100     !C?              申请 C 设备，同上
 * 0x45      01 000101     end              可执行文件结束，撤销进程
 * 0x46-0xFF 其余          ——               保留 / 非法指令，执行时报错
 * </pre>
 *
 * <p>设计说明：<br>
 * 1. 一条指令恰好 1 字节，与"每个盘块 64 字节、超过 64 条指令再分配一个盘块"完全对齐；<br>
 * 2. 赋值立即数取 6 位（0~63），覆盖指导书"数值不用太大，一位数、两位数即可"的要求；<br>
 * 3. {@code !X?} 是双字节指令，第 2 字节为设备使用时间（同样是 1 字节，仍满足"指令按字节存储"）。</p>
 */
public final class Opcode {

    private Opcode() {
    }

    /** 操作码掩码：高 2 位。 */
    public static final int OP_MASK = 0xC0;
    /** 操作数掩码：低 6 位。 */
    public static final int OPERAND_MASK = 0x3F;

    /** x=? 赋值（操作码 00，立即数在低 6 位）。 */
    public static final int OP_ASSIGN = 0x00;
    /** x++ 。 */
    public static final int OP_INC = 0x40;
    /** x-- 。 */
    public static final int OP_DEC = 0x41;
    /** !A? 申请 A 设备。 */
    public static final int OP_DEV_A = 0x42;
    /** !B? 申请 B 设备。 */
    public static final int OP_DEV_B = 0x43;
    /** !C? 申请 C 设备。 */
    public static final int OP_DEV_C = 0x44;
    /** end 结束。 */
    public static final int OP_END = 0x45;

    /** 单字节指令的全部合法取值（用于校验与反汇编表）。 */
    private static final String[] SINGLE_BYTE = new String[256];

    static {
        for (int i = 0; i < 256; i++) {
            SINGLE_BYTE[i] = "非法指令";
        }
        for (int v = 0; v <= OPERAND_MASK; v++) {
            SINGLE_BYTE[v] = "x=" + v;
        }
        SINGLE_BYTE[OP_INC] = "x++";
        SINGLE_BYTE[OP_DEC] = "x--";
        SINGLE_BYTE[OP_DEV_A] = "!A?";
        SINGLE_BYTE[OP_DEV_B] = "!B?";
        SINGLE_BYTE[OP_DEV_C] = "!C?";
        SINGLE_BYTE[OP_END] = "end";
    }

    /** 判断某字节是否为合法的单字节指令（0x00~0x45，且 0x3F 之后无空洞）。 */
    public static boolean isLegalSingleByte(int b) {
        b &= 0xFF;
        return b <= OP_END;
    }

    /** 判断某字节是否为设备申请指令（需要读取紧随其后的 1 字节时间）。 */
    public static boolean isDevice(int b) {
        b &= 0xFF;
        return b == OP_DEV_A || b == OP_DEV_B || b == OP_DEV_C;
    }

    /** 该字节对应的操作码（高 2 位）。 */
    public static int op(int b) {
        return b & OP_MASK;
    }

    /** 该字节携带的操作数（低 6 位）。 */
    public static int operand(int b) {
        return b & OPERAND_MASK;
    }

    public static String deviceName(int b) {
        return switch (b & 0xFF) {
            case OP_DEV_A -> "A";
            case OP_DEV_B -> "B";
            case OP_DEV_C -> "C";
            default -> "?";
        };
    }

    /** 设备类型名 → 编码。 */
    public static int deviceOpcode(String type) {
        return switch (type.toUpperCase()) {
            case "A" -> OP_DEV_A;
            case "B" -> OP_DEV_B;
            case "C" -> OP_DEV_C;
            default -> throw new IllegalArgumentException("未知设备类型：" + type);
        };
    }

    /** 反汇编：单字节指令的助记符。 */
    public static String mnemonic(int b) {
        return SINGLE_BYTE[b & 0xFF];
    }
}
