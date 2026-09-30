package cn.edu.scau.os.instruction;

import java.util.ArrayList;
import java.util.List;

/**
 * "汇编 / 编译"的模拟过程：把人类可读的指令文本编译成 1 字节机器码。
 *
 * <p>指导书原文："需自行思考如何把上述 5 种指令用一个字节表示并存储在磁盘块里，
 * 其实这是一个'汇编编译'的模拟过程。"本类就是这个过程的实现。</p>
 *
 * <p>支持的源文本写法（大小写不敏感，允许前置/后置空白，{@code //} 或 {@code #} 起注释）：</p>
 * <pre>
 *   x=5        x = 5      赋值
 *   x++        inc        x 加 1
 *   x--        dec        x 减 1
 *   !A3        !a 3       申请 A 设备 3 个时间单位
 *   end                   结束
 * </pre>
 */
public final class Assembler {

    private Assembler() {
    }

    /** 一条汇编结果：机器码 + 与源码行对应的清单（便于界面显示"编译过程"）。 */
    public record Result(byte[] code, List<String> listing, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public static Result assemble(String source) {
        List<String> listing = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<Byte> code = new ArrayList<>();

        int lineNo = 0;
        for (String rawLine : source.split("\\R")) {
            lineNo++;
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            int at = code.size();
            try {
                encodeOne(line, code, listing, at);
            } catch (RuntimeException e) {
                errors.add("第 " + lineNo + " 行 [" + line + "]：" + e.getMessage());
            }
        }
        byte[] bytes = new byte[code.size()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = code.get(i);
        }
        return new Result(bytes, listing, errors);
    }

    /** 把一行源码编码进 code，并追加清单行。 */
    private static void encodeOne(String line, List<Byte> code, List<String> listing, int at) {
        String t = line.replace(" ", "");
        String lower = t.toLowerCase();

        if (lower.equals("end")) {
            code.add((byte) Opcode.OP_END);
            listing.add(hex(at) + ": " + mn(Opcode.OP_END) + "\t(1B)\tend");
            return;
        }
        if (lower.equals("x++") || lower.equals("inc") || lower.equals("++")) {
            code.add((byte) Opcode.OP_INC);
            listing.add(hex(at) + ": " + mn(Opcode.OP_INC) + "\t(1B)\tx++");
            return;
        }
        if (lower.equals("x--") || lower.equals("dec") || lower.equals("--")) {
            code.add((byte) Opcode.OP_DEC);
            listing.add(hex(at) + ": " + mn(Opcode.OP_DEC) + "\t(1B)\tx--");
            return;
        }
        if (lower.startsWith("x=")) {
            int v = parseInt(lower.substring(2), "赋值立即数");
            requireRange(v, 0, Opcode.OPERAND_MASK, "赋值立即数 x=? 只支持 0~63（低 6 位）");
            int b = Opcode.OP_ASSIGN | v;
            code.add((byte) b);
            listing.add(hex(at) + ": " + mn(b) + "\t(1B)\tx=" + v);
            return;
        }
        if (lower.startsWith("!")) {
            if (lower.length() < 3) {
                throw new IllegalArgumentException("设备指令格式应为 !A3 / !B5 / !C2");
            }
            char dev = lower.charAt(1);
            int opcode = Opcode.deviceOpcode(String.valueOf(dev));
            int dur = parseInt(lower.substring(2), "设备使用时间");
            requireRange(dur, 0, Opcode.OPERAND_MASK, "设备使用时间支持 0~63");
            code.add((byte) opcode);
            code.add((byte) dur);
            listing.add(hex(at) + ": " + mn(opcode) + " " + dur + "\t(2B)\t!" + Character.toUpperCase(dev) + dur);
            return;
        }
        throw new IllegalArgumentException("无法识别的指令（仅支持 x=? / x++ / x-- / !A? / !B? / !C? / end）");
    }

    /** 反汇编整个字节数组，用于"显示文件内容"与磁盘查看。 */
    public static String disassemble(byte[] code) {
        StringBuilder sb = new StringBuilder();
        int pc = 0;
        while (pc < code.length) {
            int b = code[pc] & 0xFF;
            if (Opcode.isDevice(b)) {
                int dur = (pc + 1 < code.length) ? (code[pc + 1] & 0xFF) : -1;
                sb.append(String.format("%04X: %s %s%n", pc, Opcode.mnemonic(b), dur < 0 ? "<缺失参数>" : dur));
                pc += 2;
            } else {
                sb.append(String.format("%04X: %s%n", pc, Opcode.mnemonic(b)));
                pc += 1;
            }
        }
        return sb.toString();
    }

    /** 解码偏移 offset 处的一条指令；不检查是否越界（由调用者保证）。 */
    public static Instruction decode(byte[] code, int offset) {
        int b = code[offset] & 0xFF;
        if (Opcode.isDevice(b)) {
            int dur = (offset + 1 < code.length) ? (code[offset + 1] & 0xFF) : 0;
            return new Instruction(offset, b, dur, 2, Opcode.mnemonic(b) + " " + dur);
        }
        return new Instruction(offset, b, Opcode.operand(b), 1, Opcode.mnemonic(b));
    }

    // ---------------- 辅助 ----------------

    private static String stripComment(String s) {
        int i = s.indexOf("//");
        int j = s.indexOf('#');
        int cut = -1;
        if (i >= 0) {
            cut = i;
        }
        if (j >= 0 && (cut < 0 || j < cut)) {
            cut = j;
        }
        return cut >= 0 ? s.substring(0, cut) : s;
    }

    private static int parseInt(String s, String what) {
        String v = s.trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException(what + "缺失");
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + "不是合法整数：" + v);
        }
    }

    private static void requireRange(int v, int lo, int hi, String msg) {
        if (v < lo || v > hi) {
            throw new IllegalArgumentException(msg + "，实际 " + v);
        }
    }

    private static String hex(int v) {
        return String.format("%04X", v);
    }

    private static String mn(int b) {
        return String.format("0x%02X", b & 0xFF);
    }
}
