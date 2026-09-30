package cn.edu.scau.os.disk;

/**
 * 磁盘布局常量 —— 全项目唯一的"盘位定义"来源。
 *
 * <h2>指导书原文的一处内部矛盾（必读）</h2>
 * <p>指导书同时给出两个条件：</p>
 * <ol>
 *   <li>"第 0、1 块存放文件分配表（FAT），第 2 块存放根目录"；</li>
 *   <li>"文件分配表中一项需要 1 字节，而磁盘有 256 块，因而有 256 项"。</li>
 * </ol>
 * <p>这两个条件不能同时成立：256 项 × 1 字节 = <b>256 字节 = 4 个盘块</b>，
 * 而 2 个盘块只有 128 字节，最多容纳 128 项。因此必须在两者之间取舍。</p>
 *
 * <h2>本项目的取舍</h2>
 * <p>保留"FAT 有 256 项、覆盖全部 256 个盘块"这一<b>功能性</b>要求
 * （否则 128 项无法为 256 块建立链接表，显式链接结构直接不成立），
 * 把 FAT 区扩展为 4 个盘块（0~3），根目录顺延到第 {@link #ROOT_BLOCK} 块，数据区从第 5 块开始。
 * 换算关系：FAT 区 4 块 × 64 字节 = 256 字节 = 256 项，正好放满，不多不少。</p>
 *
 * <h2>如何切换成另一种解释</h2>
 * <p>若指导教师坚持"FAT 恰好占第 0、1 块"，则把下面的常量改为：</p>
 * <pre>
 *   FAT_BLOCKS = 2;  ROOT_BLOCK = 2;  FIRST_DATA_BLOCK = 3;
 * </pre>
 * <p>并把 {@link FileSystem} 中 FAT 大小改为 128（即只管理盘块 0~127）。
 * 全项目盘位均由本类常量派生，改这里即可，无需改动其它文件。</p>
 */
public final class DiskLayout {

    private DiskLayout() {
    }

    // ---------------- FAT 特殊值（1 字节，取值 0~255） ----------------

    /** 空闲盘块。 */
    public static final int FAT_FREE = 0;
    /** 文件结束（链尾）。 */
    public static final int FAT_EOC = 255;
    /** 坏块标记。 */
    public static final int FAT_BAD = 254;
    /** 系统保留（FAT 自身、根目录所占的块）。 */
    public static final int FAT_RESERVED = 253;

    // ---------------- 盘位布局 ----------------

    /** FAT 占用的盘块数。 */
    public static final int FAT_BLOCKS = 4;
    /** FAT 起始盘块。 */
    public static final int FAT_START_BLOCK = 0;
    /** 根目录所在盘块。 */
    public static final int ROOT_BLOCK = FAT_BLOCKS; // = 4
    /** 第一个可分配给文件的盘块（跳过 FAT 区与根目录）。 */
    public static final int FIRST_DATA_BLOCK = ROOT_BLOCK + 1; // = 5
    /** FAT 表项数 = 磁盘块数。 */
    public static final int FAT_ENTRIES = VirtualDisk.BLOCK_COUNT;

    // ---------------- 目录项布局（固定 8 字节，指导书规定） ----------------

    /** 目录项字节数。 */
    public static final int ENTRY_SIZE = 8;
    /** 名称字段长度（目录名或文件名）。 */
    public static final int NAME_LEN = 3;
    /** 扩展名字段长度（目录没有扩展名，填空格）。 */
    public static final int EXT_LEN = 1;
    /** 属性字段长度。 */
    public static final int ATTR_LEN = 1;
    /** 起始盘号字段长度。 */
    public static final int START_LEN = 1;
    /** 文件长度字段长度（单位：字节，原型阶段用 2 字节记录真实字节数）。 */
    public static final int LENGTH_LEN = 2;

    /** 每个盘块能放的目录项个数。 */
    public static final int ENTRIES_PER_BLOCK = VirtualDisk.BLOCK_SIZE / ENTRY_SIZE; // = 8
    /** 根目录固定 1 块，最多 8 项（指导书规定）。 */
    public static final int ROOT_MAX_ENTRIES = ENTRIES_PER_BLOCK; // = 8

    // ---------------- 属性字节的位定义（指导书给出） ----------------

    /** 只读文件。 */
    public static final int ATTR_READONLY = 0x01;
    /** 系统文件。 */
    public static final int ATTR_SYSTEM = 0x02;
    /** 可读可写的普通文件。 */
    public static final int ATTR_NORMAL = 0x04;
    /** 目录登记项（这一位置 1 表示本项是目录而不是文件）。 */
    public static final int ATTR_DIRECTORY = 0x08;

    /** 空闲目录项首字节（值为 0 表示该目录项为空）。 */
    public static final byte ENTRY_EMPTY = 0x00;

    /** 可执行文件扩展名。 */
    public static final String EXT_EXECUTABLE = "e";

    /** 布局说明文本，供界面/报告显示。 */
    public static String describe() {
        return String.format(
                "盘块 %d 字节 × %d 块 = %d 字节；FAT 占盘块 %d~%d（%d 项 × 1 字节）；"
                        + "根目录占盘块 %d（最多 %d 项 × %d 字节）；数据区自盘块 %d 起。",
                VirtualDisk.BLOCK_SIZE, VirtualDisk.BLOCK_COUNT, VirtualDisk.TOTAL_BYTES,
                FAT_START_BLOCK, FAT_START_BLOCK + FAT_BLOCKS - 1, FAT_ENTRIES,
                ROOT_BLOCK, ROOT_MAX_ENTRIES, ENTRY_SIZE, FIRST_DATA_BLOCK);
    }
}
