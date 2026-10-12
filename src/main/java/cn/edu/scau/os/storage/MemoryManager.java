package cn.edu.scau.os.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/** 512字节用户区、链表动态分区表和基址/限长保护。调用者持有内核模拟锁。 */
public class MemoryManager {
    public static final int USER_AREA_SIZE = 512;
    public static final class Partition {
        public int start;
        public int size;
        public int pid = -1;
        Partition(int start, int size) { this.start = start; this.size = size; }
        public boolean isFree() { return pid < 0; }
        public int end() { return start + size - 1; }
        public String toString() {
            return isFree() ? String.format("空闲[%d..%d] %dB", start, end(), size)
                    : String.format("P%d[%d..%d] %dB", pid, start, end(), size);
        }
    }
    public enum Fit { FIRST, NEXT, BEST }
    private final LinkedList<Partition> partitions = new LinkedList<>();
    private final byte[] userArea = new byte[USER_AREA_SIZE];
    private Fit fit = Fit.FIRST;
    private int nextFitAddress;

    public MemoryManager() { reset(); }
    public Fit fit() { return fit; }
    public void setFit(Fit fit) { this.fit = java.util.Objects.requireNonNull(fit); }
    public void reset() {
        partitions.clear();
        partitions.add(new Partition(0, USER_AREA_SIZE));
        Arrays.fill(userArea, (byte) 0);
        nextFitAddress = 0;
    }
    public Partition allocate(int pid, int size) {
        if (pid < 0 || size <= 0 || size > USER_AREA_SIZE) throw new IllegalArgumentException("程序大小必须为1~512字节");
        if (partitionOf(pid) != null) throw new IllegalStateException("进程已有内存：" + pid);
        Partition picked = null;
        int start = -1;
        if (fit == Fit.NEXT) {
            for (Partition p : partitions) {
                int candidate = Math.max(p.start, nextFitAddress);
                if (p.isFree() && (long) candidate + size <= p.start + p.size) {
                    picked = p; start = candidate; break;
                }
            }
        }
        if (picked == null) {
            for (Partition p : partitions) {
                if (p.isFree() && p.size >= size && (picked == null || fit == Fit.BEST && p.size < picked.size)) {
                    picked = p; start = p.start;
                    if (fit != Fit.BEST) break;
                }
            }
        }
        if (picked == null) throw new IllegalStateException("内存不足：无法为P" + pid + "分配" + size + "字节");
        int index = partitions.indexOf(picked);
        int end = picked.start + picked.size;
        if (start > picked.start) {
            partitions.add(index++, new Partition(picked.start, start - picked.start));
        }
        picked.start = start; picked.size = size; picked.pid = pid;
        if (start + size < end) partitions.add(index + 1, new Partition(start + size, end - start - size));
        nextFitAddress = (start + size) % USER_AREA_SIZE;
        return picked;
    }
    public void load(int pid, byte[] image) {
        Partition p = partitionOf(pid);
        if (p == null || image.length != p.size) throw new IllegalArgumentException("程序与分区长度不一致");
        System.arraycopy(image, 0, userArea, p.start, image.length);
    }
    public int readByte(int pid, int logicalAddress) {
        Partition p = partitionOf(pid);
        if (p == null || logicalAddress < 0 || logicalAddress >= p.size)
            throw new IllegalStateException("存储保护：P" + pid + "取指越界，PC=" + logicalAddress);
        return userArea[p.start + logicalAddress] & 255;
    }
    public void free(int pid) {
        for (Partition p : partitions) {
            if (p.pid == pid) {
                Arrays.fill(userArea, p.start, p.start + p.size, (byte) 0);
                p.pid = -1;
            }
        }
        var iterator = partitions.listIterator();
        Partition previous = null;
        while (iterator.hasNext()) {
            Partition current = iterator.next();
            if (previous != null && previous.isFree() && current.isFree()) {
                previous.size += current.size;
                iterator.remove();
            } else previous = current;
        }
    }
    public boolean checkAccess(int pid, int address, int length) {
        Partition p = partitionOf(pid);
        return p != null && length > 0 && address >= p.start && (long) address + length <= p.start + p.size;
    }
    public Partition partitionOf(int pid) {
        for (Partition p : partitions) if (p.pid == pid) return p;
        return null;
    }
    public List<Partition> partitions() {
        List<Partition> copy = new ArrayList<>();
        for (Partition p : partitions) {
            Partition c = new Partition(p.start, p.size); c.pid = p.pid; copy.add(c);
        }
        return copy;
    }
    public int freeBytes() {
        int total = 0;
        for (Partition p : partitions) if (p.isFree()) total += p.size;
        return total;
    }
    public int usedBytes() { return USER_AREA_SIZE - freeBytes(); }
    public int[] ownerMap() {
        int[] map = new int[USER_AREA_SIZE];
        Arrays.fill(map, -1);
        for (Partition p : partitions) if (!p.isFree()) Arrays.fill(map, p.start, p.start + p.size, p.pid);
        return map;
    }
}
