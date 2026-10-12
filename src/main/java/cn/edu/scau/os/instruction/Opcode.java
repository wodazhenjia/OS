package cn.edu.scau.os.instruction;

/** 单字节指令：赋值 0~99；100/101/102 为加、减、结束；设备高半字节标识类型。 */
public final class Opcode {
    private Opcode() { }
    public static final int OPERAND_MASK = 99;
    public static final int OP_ASSIGN = 0;
    public static final int OP_INC = 0x64;
    public static final int OP_DEC = 0x65;
    public static final int OP_END = 0x66;
    public static final int OP_DEV_A = 0x80;
    public static final int OP_DEV_B = 0x90;
    public static final int OP_DEV_C = 0xA0;

    public static boolean isLegalSingleByte(int b) {
        b &= 0xFF;
        return b <= OP_END || isDevice(b);
    }
    public static boolean isDevice(int b) {
        b &= 0xFF;
        int type = b & 0xF0;
        return (type == OP_DEV_A || type == OP_DEV_B || type == OP_DEV_C) && (b & 15) <= 9;
    }
    public static int operand(int b) { return isDevice(b) ? b & 15 : b & 0xFF; }
    public static String deviceName(int b) {
        return switch (b & 0xF0) {
            case OP_DEV_A -> "A";
            case OP_DEV_B -> "B";
            case OP_DEV_C -> "C";
            default -> "?";
        };
    }
    public static int deviceOpcode(String type) {
        return switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "A" -> OP_DEV_A;
            case "B" -> OP_DEV_B;
            case "C" -> OP_DEV_C;
            default -> throw new IllegalArgumentException("未知设备类型：" + type);
        };
    }
    public static String mnemonic(int b) {
        b &= 0xFF;
        if (b <= OPERAND_MASK) return "x=" + b;
        if (isDevice(b)) return "!" + deviceName(b) + operand(b);
        return switch (b) {
            case OP_INC -> "x++";
            case OP_DEC -> "x--";
            case OP_END -> "end";
            default -> "非法指令";
        };
    }
}
