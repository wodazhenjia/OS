package cn.edu.scau.os.disk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** 一个256块物理磁盘上的连续分区；分区表存入同名侧文件，不改动旧版磁盘布局。 */
public final class PartitionManager {
    public record Partition(String name, int start, int blocks) { }
    private final VirtualDisk disk;
    private final FileSystem fs;
    private List<Partition> partitions = List.of(new Partition("main", 0, VirtualDisk.BLOCK_COUNT));
    private String active = "main";
    private boolean loaded;

    public PartitionManager(VirtualDisk disk, FileSystem fs) { this.disk = disk; this.fs = fs; }
    public static Path tablePath(Path image) { return image.resolveSibling(image.getFileName() + ".partitions"); }
    public String activeName() { return active; }

    /** 无分区表的旧盘视为单个main分区。错误的分区表会报错，绝不自动覆盖。 */
    public void load() {
        if (loaded) return;
        Path table = tablePath(disk.file());
        if (Files.exists(table)) {
            Properties data = new Properties();
            try (var input = Files.newInputStream(table)) { data.load(input); }
            catch (IOException e) { throw new UncheckedIOException("读取分区表失败", e); }
            try {
                if (!"1".equals(data.getProperty("version"))) throw new IllegalArgumentException("分区表版本错误");
                int count = Integer.parseInt(data.getProperty("count"));
                if (count < 1 || count > VirtualDisk.BLOCK_COUNT / 6) throw new IllegalArgumentException("分区数量错误");
                List<Partition> entries = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    String[] row = data.getProperty("partition." + i, "").split(",", -1);
                    if (row.length != 3) throw new IllegalArgumentException("分区项格式错误");
                    entries.add(new Partition(name(row[0]), Integer.parseInt(row[1]), Integer.parseInt(row[2])));
                }
                validate(entries);
                String selected = name(data.getProperty("active", ""));
                if (entries.stream().noneMatch(p -> p.name().equals(selected))) throw new IllegalArgumentException("活动分区不存在");
                if (!disk.exists()) throw new IllegalArgumentException("分区表存在但磁盘文件缺失");
                partitions = List.copyOf(entries);
                active = selected;
            } catch (RuntimeException e) { throw new IllegalStateException("分区表损坏，原文件未修改：" + table, e); }
        }
        select(require(active));
        loaded = true;
    }

    public String describe() {
        StringBuilder text = new StringBuilder("物理磁盘：256块 × 64字节 = 16384字节\n");
        text.append("分区       物理起止块    总块数  数据块数\n");
        for (Partition p : sorted(partitions)) {
            text.append(String.format("%s %-8s %3d-%3d      %3d     %3d%n",
                    p.name().equals(active) ? "*" : " ", p.name(), p.start(), p.start() + p.blocks() - 1,
                    p.blocks(), p.blocks() - DiskLayout.FIRST_DATA_BLOCK));
        }
        int free = VirtualDisk.BLOCK_COUNT - partitions.stream().mapToInt(Partition::blocks).sum();
        return text.append("* 当前分区；未分配物理盘块：").append(free)
                .append("\n当前分区已用数据块：").append(fs.usedBlocks()).toString();
    }

    public void create(String label, int blocks) {
        String id = name(label);
        if (partitions.stream().anyMatch(p -> p.name().equals(id))) throw new IllegalArgumentException("分区已存在：" + id);
        if (blocks < 6 || blocks > VirtualDisk.BLOCK_COUNT) throw new IllegalArgumentException("分区大小必须为6～256块（含5块系统区）");
        int gap = gap(blocks);
        Partition current = require(active);
        List<Partition> next = new ArrayList<>(partitions);
        int start;
        if (gap >= 0) start = gap;
        else {
            int remaining = current.blocks() - blocks;
            if (remaining < 6) throw new IllegalStateException("没有足够的连续空闲空间");
            start = current.start() + remaining;
            next.set(next.indexOf(current), new Partition(current.name(), current.start(), remaining));
        }
        Partition created = new Partition(id, start, blocks);
        next.add(created);
        update(next, active, () -> {
            if (gap < 0) fs.shrinkPartition(current.blocks() - blocks);
            select(created);
            new FileSystem(disk).format();
            select(next.stream().filter(p -> p.name().equals(active)).findFirst().orElseThrow());
            fs.load();
        });
    }

    public void delete(String label) {
        Partition removed = require(name(label));
        if (removed.name().equals(active)) throw new IllegalStateException("不能删除当前分区，请先切换到其他分区");
        List<Partition> next = new ArrayList<>(partitions);
        next.remove(removed);
        Partition current = require(active);
        update(next, active, () -> { select(removed); disk.clear(); select(current); });
    }

    public void use(String label) {
        Partition selected = require(name(label));
        if (selected.name().equals(active)) return;
        update(partitions, selected.name(), () -> { select(selected); fs.load(); });
    }

    private void select(Partition p) { disk.selectPartition(p.start(), p.blocks()); }
    private Partition require(String label) {
        return partitions.stream().filter(p -> p.name().equals(label)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("分区不存在：" + label));
    }
    private static String name(String label) {
        if (label == null || !label.matches("[A-Za-z][A-Za-z0-9]{0,7}"))
            throw new IllegalArgumentException("分区名为1～8个字母或数字，且以字母开头");
        return label.toLowerCase(Locale.ROOT);
    }
    private static List<Partition> sorted(List<Partition> entries) {
        return entries.stream().sorted(Comparator.comparingInt(Partition::start)).toList();
    }
    private static void validate(List<Partition> entries) {
        HashSet<String> names = new HashSet<>();
        int end = 0;
        for (Partition p : sorted(entries)) {
            if (!names.add(name(p.name())) || p.blocks() < 6 || p.blocks() > VirtualDisk.BLOCK_COUNT
                    || p.start() < end || p.start() > VirtualDisk.BLOCK_COUNT - p.blocks())
                throw new IllegalArgumentException("分区重叠、重名或越界");
            end = p.start() + p.blocks();
        }
    }
    private int gap(int blocks) {
        int end = 0;
        for (Partition p : sorted(partitions)) {
            if (p.start() - end >= blocks) return end;
            end = p.start() + p.blocks();
        }
        return VirtualDisk.BLOCK_COUNT - end >= blocks ? end : -1;
    }

    /** 磁盘更新失败或分区表保存失败，恢复整盘字节和原来的活动视图。 */
    private void update(List<Partition> next, String selected, Runnable operation) {
        validate(next);
        Partition previous = require(active);
        Path backup = disk.backup("simos-partition-undo-");
        boolean discard = true;
        try {
            operation.run();
            fs.validate();
            disk.flushAll();
            save(next, selected);
            partitions = List.copyOf(next);
            active = selected;
        } catch (RuntimeException failure) {
            try { disk.restore(backup); select(previous); fs.load(); }
            catch (RuntimeException restoreFailure) { discard = false; failure.addSuppressed(restoreFailure); }
            throw failure;
        } finally { if (discard) disk.discardBackup(backup); }
    }
    private void save(List<Partition> entries, String selected) {
        Properties data = new Properties();
        data.setProperty("version", "1"); data.setProperty("active", selected);
        data.setProperty("count", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            Partition p = entries.get(i);
            data.setProperty("partition." + i, p.name() + "," + p.start() + "," + p.blocks());
        }
        Path table = tablePath(disk.file());
        try {
            Path temporary = Files.createTempFile(table.getParent(), "simos-partitions-", ".tmp");
            try {
                try (var output = Files.newOutputStream(temporary)) { data.store(output, "SimOS partition table"); }
                try { Files.move(temporary, table, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, table, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException e) { throw new UncheckedIOException("保存分区表失败", e); }
    }
}
