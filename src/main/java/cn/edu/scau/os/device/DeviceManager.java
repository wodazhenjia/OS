package cn.edu.scau.os.device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备管理：A / B / C 三种独占型设备，A 2 个、B 3 个、C 3 个。
 *
 * <p>指导书要点：</p>
 * <ul>
 *   <li>设备的申请由可执行文件中的 {@code !X?} 指令引起；</li>
 *   <li>有空闲设备时分配设备，然后<b>进程阻塞</b>，同时"设备使用倒计时"开始；</li>
 *   <li>无空闲设备时进程进入该设备的等待队列，直到别的进程释放设备才分配；</li>
 *   <li>倒计时到 0：释放设备并唤醒占用它的进程；同时把等待该设备的进程唤醒（置就绪）；</li>
 *   <li>设备使用完后<b>立即释放</b>，后续指令要再次使用该设备时需重新申请；</li>
 *   <li>不考虑死锁（本模拟为单设备申请，不存在循环等待）。</li>
 * </ul>
 */
public class DeviceManager {

    /** 单个设备实体。 */
    public static final class Device {
        public final String type;
        public final int index;
        /** 占用该设备的进程号，-1 表示空闲。 */
        public int owner = -1;
        /** 设备使用倒计时（时间单位）。 */
        public int remaining;

        Device(String type, int index) {
            this.type = type;
            this.index = index;
        }

        public boolean isFree() {
            return owner < 0;
        }

        public String name() {
            return type + index;
        }
    }

    private final List<Device> devices = new ArrayList<>();
    /** 设备类型 → 等待该类型设备的进程队列（先进先出）。 */
    private final Map<String, List<Integer>> waitQueue = new HashMap<>();

    public DeviceManager() {
        addDevices("A", 2);
        addDevices("B", 3);
        addDevices("C", 3);
        for (String t : List.of("A", "B", "C")) {
            waitQueue.put(t, new ArrayList<>());
        }
    }

    private void addDevices(String type, int count) {
        for (int i = 1; i <= count; i++) {
            devices.add(new Device(type, i));
        }
    }

    public List<Device> devices() {
        return devices;
    }

    public List<Device> devicesOfType(String type) {
        List<Device> out = new ArrayList<>();
        for (Device d : devices) {
            if (d.type.equalsIgnoreCase(type)) {
                out.add(d);
            }
        }
        return out;
    }

    public List<Integer> waitQueue(String type) {
        List<Integer> q = waitQueue.get(type.toUpperCase());
        return q == null ? new ArrayList<>() : new ArrayList<>(q);
    }

    /** 全部等待队列的快照：设备类型 → 进程号列表。 */
    public Map<String, List<Integer>> waitQueues() {
        Map<String, List<Integer>> out = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : waitQueue.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return out;
    }

    /** 找个空闲的该类型设备；没有返回 null。 */
    public Device findFree(String type) {
        for (Device d : devices) {
            if (d.type.equalsIgnoreCase(type) && d.isFree()) {
                return d;
            }
        }
        return null;
    }

    /**
     * 把设备分配给进程。
     *
     * @return 分配到的设备；若失败返回 null
     */
    public Device assign(String type, int pid, int duration) {
        Device d = findFree(type);
        if (d == null) {
            return null;
        }
        d.owner = pid;
        d.remaining = duration;
        return d;
    }

    /** 进程加入某类设备的等待队列。 */
    public void enqueueWait(String type, int pid) {
        List<Integer> q = waitQueue.computeIfAbsent(type.toUpperCase(), k -> new ArrayList<>());
        if (!q.contains(pid)) {
            q.add(pid);
        }
    }

    /**
     * 时间推进一个单位：所有占用中的设备倒计时减 1。
     *
     * @return 本时间单位内倒计时归零、需要释放的设备列表
     */
    public List<Device> tick() {
        List<Device> finished = new ArrayList<>();
        for (Device d : devices) {
            if (d.isFree()) {
                continue;
            }
            d.remaining--;
            if (d.remaining <= 0) {
                d.remaining = 0;
                finished.add(d);
            }
        }
        return finished;
    }

    /**
     * 释放设备并把等待队列队首进程摘出（"同时将等待该设备的另一个进程唤醒"）。
     *
     * @return 被唤醒（置就绪）的进程号；没有等待者返回 -1
     */
    public int release(Device d) {
        String type = d.type;
        int prevOwner = d.owner;
        d.owner = -1;
        d.remaining = 0;
        List<Integer> q = waitQueue.get(type);
        if (q == null || q.isEmpty()) {
            return -1;
        }
        int next = q.remove(0);
        return next;
    }

    /** 进程被撤销/唤醒时，把它从所有等待队列里清掉。 */
    public void removeFromAllQueues(int pid) {
        for (List<Integer> q : waitQueue.values()) {
            q.remove((Integer) pid);
        }
    }

    /** 某进程正在占用的设备，没有则返回 null。 */
    public Device deviceOf(int pid) {
        for (Device d : devices) {
            if (d.owner == pid) {
                return d;
            }
        }
        return null;
    }
}
