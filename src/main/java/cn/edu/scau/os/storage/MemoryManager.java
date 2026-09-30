package cn.edu.scau.os.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * 存储管理：主存用户区 512 字节 + 动态分区 + 首次适应（First Fit）分配与回收 + 存储保护。
 *
 * <p>指导书要求：</p>
 * <ul>
 *   <li>用链表模拟内存空间分配表；采用动态分区存储管理方式；首次适应、下次适应、最佳适配均可；</li>
 *   <li>主存分为系统区（PCB 区 + 内存分配表）与用户区；<b>用户区数组大小为 512 字节</b>；</li>
 *   <li>示例：36 字节的 bb.e 分配 36 字节；110 字节的 cc.e 若空间不足则"无法载入内存"而等待。</li>
 * </ul>
 *
 * <p>本实现同时保留"空闲分区链表"与"按字节占用表"两份视图：前者用于分配算法，
 * 后者直接支撑界面上的彩色内存示意图。</p>
 */
public class MemoryManager {

    /** 用户区总容量（字节）。 */
    public static final int USER_AREA_SIZE = 512;

    /** 一个内存分区。 */
    public static final class Partition {
        public int start;
        public int size;
        public int pid = -1; // -1 表示空闲

        Partition(int start, int size) {
            this.start = start;
            this.size = size;
        }

        public boolean isFree() {
            return pid < 0;
        }

        public int end() {
            return start + size - 1;
        }

        @Override
        public String toString() {
            return isFree()
                    ? String.format("空闲[%d..%d] %dB", start, end(), size)
                    : String.format("P%d[%d..%d] %dB", pid, start, end(), size);
        }
    }

    /** 分配算法。 */
    public enum Fit {FIRST, NEXT, BEST}

    private final List<Partition> partitions = new ArrayList<>();
    private Fit fit = Fit.FIRST;
    private int nextFitCursor;

    public MemoryManager() {
        partitions.add(new Partition(0, USER_AREA_SIZE));
    }

    public Fit fit() {
        return fit;
    }

    public void setFit(Fit fit) {
        this.fit = fit;
    }

    /**
     * 申请内存。
     *
     * @param pid  进程标识
     * @param size 需要的字节数
     * @return 分配到的分区
     * @throws IllegalStateException 空间不足（调用者据此把进程置为"无法载入内存"的等待状态）
     */
    public Partition allocate(int pid, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("申请内存大小必须为正数，实际 " + size);
        }
        int idx = pick(size);
        if (idx < 0) {
            throw new IllegalStateException("内存不足：无法为 P" + pid + " 分配 " + size + " 字节");
        }
        Partition p = partitions.get(idx);
        int remain = p.size - size;
        p.pid = pid;
        p.size = size;
        if (remain > 0) {
            partitions.add(idx + 1, new Partition(p.start + size, remain));
        }
        if (fit == Fit.NEXT) {
            nextFitCursor = idx + 1;
        }
        return p;
    }

    /** 按当前算法挑选可用分区下标，找不到返回 -1。 */
    private int pick(int size) {
        switch (fit) {
            case FIRST:
                for (int i = 0; i < partitions.size(); i++) {
                    Partition p = partitions.get(i);
                    if (p.isFree() && p.size >= size) {
                        return i;
                    }
                }
                return -1;
            case NEXT:
                int n = partitions.size();
                for (int k = 0; k < n; k++) {
                    int i = (nextFitCursor + k) % n;
                    Partition p = partitions.get(i);
                    if (p.isFree() && p.size >= size) {
                        return i;
                    }
                }
                return -1;
            case BEST:
                int best = -1;
                int bestSize = Integer.MAX_VALUE;
                for (int i = 0; i < partitions.size(); i++) {
                    Partition p = partitions.get(i);
                    if (p.isFree() && p.size >= size && p.size < bestSize) {
                        best = i;
                        bestSize = p.size;
                    }
                }
                return best;
            default:
                return -1;
        }
    }

    /** 回收某个进程占用的内存，并与相邻空闲分区合并。 */
    public void free(int pid) {
        for (Partition p : partitions) {
            if (p.pid == pid) {
                p.pid = -1;
            }
        }
        merge();
    }

    /** 合并相邻空闲分区。 */
    private void merge() {
        for (int i = 0; i < partitions.size() - 1; ) {
            Partition a = partitions.get(i);
            Partition b = partitions.get(i + 1);
            if (a.isFree() && b.isFree() && a.end() + 1 == b.start) {
                a.size += b.size;
                partitions.remove(i + 1);
            } else {
                i++;
            }
        }
    }

    /** 存储保护：校验某进程访问的地址范围是否落在它自己的分区内。 */
    public boolean checkAccess(int pid, int address, int length) {
        Partition p = partitionOf(pid);
        if (p == null) {
            return false;
        }
        return address >= p.start && address + length - 1 <= p.end();
    }

    public Partition partitionOf(int pid) {
        for (Partition p : partitions) {
            if (p.pid == pid) {
                return p;
            }
        }
        return null;
    }

    public List<Partition> partitions() {
        return new ArrayList<>(partitions);
    }

    /** 空闲字节总量。 */
    public int freeBytes() {
        int n = 0;
        for (Partition p : partitions) {
            if (p.isFree()) {
                n += p.size;
            }
        }
        return n;
    }

    /** 已分配字节总量。 */
    public int usedBytes() {
        return USER_AREA_SIZE - freeBytes();
    }

    /**
     * 按字节导出占用者映射：{@code owners[i]} 表示第 i 个字节属于哪个进程，-1 为空闲。
     * 界面直接用它画彩色内存条。
     */
    public int[] ownerMap() {
        int[] map = new int[USER_AREA_SIZE];
        java.util.Arrays.fill(map, -1);
        for (Partition p : partitions) {
            if (p.isFree()) {
                continue;
            }
            for (int i = p.start; i <= p.end() && i < USER_AREA_SIZE; i++) {
                map[i] = p.pid;
            }
        }
        return map;
    }
}
