package cn.edu.scau.os.kernel;

import cn.edu.scau.os.disk.DiskLayout;
import cn.edu.scau.os.disk.FileSystem;
import cn.edu.scau.os.instruction.Assembler;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 演示数据：按指导书要求准备"至少 5 个目录和 15 个文件"，以及"事先创建约 10 个可执行文件"。
 *
 * <h2>命名为什么都只有 3 个字符</h2>
 * <p>指导书规定目录项固定 8 字节，其中名字段只有 <b>3 字节</b>（另有扩展名 1 字节）。
 * 因此文件名与目录名最长 3 个字符、扩展名最长 1 个字符（可执行文件用 {@code e}）。
 * 本类中所有名字都遵守该约束；{@link FileSystem#createFile} 对超长名字会直接报错，
 * 而不是悄悄截断（截断会导致"刚创建的文件立刻找不到"）。</p>
 *
 * <h2>为什么可执行文件分散在子目录里</h2>
 * <p>根目录固定 1 块、最多只能放 8 个目录项。为了让 10 个可执行文件都能存在，
 * 把它们分散到根目录与 /aa、/bin 子目录中。</p>
 */
public final class SeedBuilder {

    private SeedBuilder() {
    }

    /** 一个演示程序：路径 + 汇编源码 + 说明。 */
    private record Prog(String path, String source, String note) {
    }

    private static final List<Prog> PROGRAMS = List.of(
            new Prog("/hi.e", """
                    x=5
                    x++
                    x++
                    x--
                    end
                    """, "简单加减：最终 x=6"),
            new Prog("/lo.e", """
                    x=1
                    x++
                    x++
                    x++
                    end
                    """, "累加：最终 x=4"),
            new Prog("/aa/big.e", """
                    x=2
                    x++
                    x++
                    x++
                    end
                    """, "子目录中的程序：最终 x=5"),
            new Prog("/aa/dev.e", """
                    x=9
                    !A3
                    x++
                    end
                    """, "申请 A 设备 3 个时间单位，结束后 x=10"),
            new Prog("/aa/dv2.e", """
                    !A5
                    x=7
                    x++
                    end
                    """, "申请 A 设备 5 个时间单位（观察等待队列）"),
            new Prog("/bin/add.e", """
                    x=3
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    end
                    """, "长程序：最终 x=13"),
            new Prog("/bin/chn.e", """
                    !B2
                    x=1
                    !C1
                    x++
                    !A1
                    x++
                    end
                    """, "依次申请 B、C、A，多次阻塞与唤醒"),
            new Prog("/bin/rod.e", """
                    !B2
                    x=8
                    end
                    """, "先申请 B 设备 2 个时间单位，再赋值"),
            new Prog("/bin/lng.e", """
                    x=1
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    x++
                    end
                    """, "较长程序：验证多盘块链接与多次时间片切换"),
            new Prog("/bin/mem.e", """
                    x=4
                    x++
                    x++
                    end
                    """, "内存占用约 3 字节，便于观察内存分配")
    );

    /** 把全部演示数据写入已经 format 过的文件系统。 */
    public static void build(FileSystem fs) {
        // ---- 5 个子目录（满足"至少 5 个目录"）----
        fs.createDirectory("/aa");
        fs.createDirectory("/dd");
        fs.createDirectory("/xx");
        fs.createDirectory("/bin");
        fs.createDirectory("/doc");

        // ---- 10 个可执行文件 ----
        // 根目录的 8 个登记项被 5 个目录 + 3 个文件占满，其余可执行文件放进子目录。
        for (Prog p : PROGRAMS) {
            Assembler.Result r = Assembler.assemble(p.source());
            if (!r.ok()) {
                throw new IllegalStateException("演示程序 " + p.path() + " 汇编失败：" + r.errors());
            }
            fs.createFile(p.path(), DiskLayout.ATTR_NORMAL, r.code());
        }

        // ---- 普通文本文件，补足"至少 15 个文件"（全部放在子目录里，避免撑满根目录）----
        // 注意：目录项的扩展名字段只有 1 字节，所以 ".txt" 这类三字符扩展名无法表示。
        // 指导书本身也只用扩展名区分"可执行文件(e)"与"目录(无扩展名)"，
        // 因此普通文本文件一律不带扩展名。
        writeText(fs, "/doc/rme", "README：用文件模拟磁盘，盘块 64 字节，共 256 块。");
        writeText(fs, "/doc/fat", "FAT 每项 1 字节：0 空闲，1~252 下一盘块，253 系统保留，254 坏块，255 结束。");
        writeText(fs, "/doc/aaa", "aaa");
        writeText(fs, "/doc/bbb", "bbb");
        writeText(fs, "/dd/cfg", "配置：时间片=6，进程上限=10，用户区=512 字节。");
        writeText(fs, "/dd/not", "SimOS 演示数据：普通文本文件，无扩展名（目录项扩展名只有 1 字节）");
        writeText(fs, "/aa/ccc", "ccc");
        writeText(fs, "/xx/yyy", "yyy");
    }

    private static void writeText(FileSystem fs, String path, String text) {
        fs.createFile(path, DiskLayout.ATTR_NORMAL, text.getBytes(StandardCharsets.US_ASCII));
    }

    /** 演示程序数量，供自检输出。 */
    public static int programCount() {
        return PROGRAMS.size();
    }
}
