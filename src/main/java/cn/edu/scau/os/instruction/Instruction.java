package cn.edu.scau.os.instruction;

/**
 * 已解码的一条指令（CPU 执行时使用）。
 *
 * @param offset 指令在可执行文件中的字节偏移
 * @param opcode 指令首字节
 * @param operand 操作数：x=? 时为立即数；!X? 时为设备使用时间
 * @param length 该指令占用的字节数（多数为 1，!X? 为 2）
 * @param text 助记符，用于界面"正在执行的指令"一栏
 */
public record Instruction(int offset, int opcode, int operand, int length, String text) {

    public boolean isAssign() {
        return opcode <= Opcode.OPERAND_MASK;
    }

    public boolean isDevice() {
        return Opcode.isDevice(opcode);
    }

    public boolean isEnd() {
        return opcode == Opcode.OP_END;
    }

    public boolean isLegal() {
        return Opcode.isLegalSingleByte(opcode) || isDevice();
    }

    public String deviceName() {
        return Opcode.deviceName(opcode);
    }
}
