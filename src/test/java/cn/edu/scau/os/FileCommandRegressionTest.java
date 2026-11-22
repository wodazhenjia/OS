package cn.edu.scau.os;

import cn.edu.scau.os.app.SafeBoot;
import cn.edu.scau.os.disk.DiskLayout;
import cn.edu.scau.os.disk.FileSystem;
import cn.edu.scau.os.disk.PartitionManager;
import cn.edu.scau.os.disk.VirtualDisk;
import cn.edu.scau.os.kernel.Kernel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Properties;

/** 所有磁盘位于临时目录，验证命令语义、跨分区隔离、失败回滚和重启。 */
public final class FileCommandRegressionTest {
    private static Path temporary;
    private static int sequence, assertions;
    public static void main(String[] args) throws Exception {
        temporary = Files.createTempDirectory("simos-file-commands-");
        paths(); moves(); partitions(); partitionFailures(); recovery();
        System.out.println("File command regression passed: " + assertions + " assertions, 5 groups");
    }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(Runnable operation, String message) {
        assertions++;
        try { operation.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }
    private static Kernel kernel() {
        Kernel k = new Kernel(temporary.resolve("kernel-" + sequence++ + ".img"));
        k.boot(42); k.setAutoArrivals(false); return k;
    }
    private static String ok(Kernel k, String command) {
        Kernel.CommandResult result = k.exec(command);
        check(result.ok(), command + ": " + result.message()); return result.message();
    }
    private static void bad(Kernel k, String command) { check(!k.exec(command).ok(), "Should reject: " + command); }
    private static void paths() {
        Kernel k = kernel();
        ok(k, "mkdir /wrk"); ok(k, "chdir /wrk");
        check(k.location().equals("main:/wrk"), "Current directory changes");
        check(ok(k, "chdir").contains("main:/wrk"), "No-argument chdir shows current directory");
        ok(k, "mkdir sub"); ok(k, "cd sub"); ok(k, "create a 中文内容");
        check(k.fs().readText(k.fs().require("/wrk/sub/a")).equals("中文内容"), "Relative create");
        check(ok(k, "type .\\a").contains("中文内容"), "Backslash and dot paths");
        ok(k, "copy a ../b"); ok(k, "change ../b ro");
        check(k.fs().require("/wrk/b").isReadonly(), "Relative copy/change");
        bad(k, "delete ../b"); ok(k, "change ../b normal"); ok(k, "delete ../b");
        bad(k, "chdir a"); bad(k, "chdir missing"); bad(k, "chdir /../../"); bad(k, "chdir sub extra");
        check(k.location().equals("main:/wrk/sub"), "Failed chdir leaves location unchanged");
        bad(k, "deldir .."); bad(k, "rmdir .");
        check(k.fs().resolve("/wrk/sub/a") != null, "Cannot delete working directory or ancestor");
        check(!ok(k, "dir").contains("[D] wrk"), "dir starts at working directory");
        ok(k, "create p.e x=2|x++|end"); ok(k, "run p.e");
        for (int i = 0; i < 12; i++) k.cpu().cycle();
        check(k.results().stream().anyMatch(s -> s.contains("x = 3")), "Relative run executes program");
        ok(k, "chdir .."); ok(k, "chdir /");
        bad(k, "chdir .."); check(k.location().equals("main:/"), "Cannot climb above root");
        ok(k, "deldir /wrk"); k.fs().validate();
    }
    private static void moves() throws Exception {
        Kernel k = kernel(); ok(k, "mkdir /wrk");
        ok(k, "create /wrk/abc " + "中".repeat(50));
        int start = k.fs().require("/wrk/abc").startBlock(), used = k.fs().usedBlocks();
        ok(k, "move /wrk/abc /dd");
        check(k.fs().resolve("/wrk/abc") == null, "Source entry removed");
        check(k.fs().require("/dd/abc").startBlock() == start && k.fs().usedBlocks() == used, "Move reuses data chain");
        check(k.fs().readText(k.fs().require("/dd/abc")).equals("中".repeat(50)), "Multiblock content retained");
        ok(k, "move /dd/abc /dd/new");
        ok(k, "change /dd/new ro"); bad(k, "move /dd/new /wrk"); ok(k, "change /dd/new normal");
        ok(k, "create /wrk/new exists");
        byte[] before = Files.readAllBytes(k.disk().file());
        bad(k, "move /dd/new /wrk/new"); bad(k, "move /dd/new /bad/abc"); bad(k, "move /dd/new /dd/long");
        check(Arrays.equals(before, Files.readAllBytes(k.disk().file())), "Rejected moves do not modify disk");
        ok(k, "mkdir /wrk/sub"); ok(k, "chdir /wrk/sub");
        ok(k, "move /wrk /dd/ren");
        check(k.location().equals("main:/dd/ren/sub"), "Moving ancestor rebases current directory");
        bad(k, "move /dd /dd/ren/sub"); bad(k, "move / /dd");
        ok(k, "create a value"); check(k.fs().resolve("/dd/ren/sub/a") != null, "Relative paths work after directory move");
        k.fs().validate();
        Kernel reloaded = new Kernel(k.disk().file()); reloaded.boot(42);
        check(ok(reloaded, "type /dd/ren/sub/a").contains("value"), "Directory move survives reopening disk");

        // 满根目录里重命名不应额外申请槽位或盘块。
        FileSystem fs = new FileSystem(new VirtualDisk(temporary.resolve("full-root.img"))); fs.format();
        for (int i = 0; i < 8; i++) fs.createFile("/f" + i, 4, new byte[]{(byte) i});
        fs.move("/f0", "/ren"); check(fs.require("/ren").length() == 1, "Rename in full root succeeds");
        rejects(() -> fs.createDirectory("/ren/sub"), "File cannot be used as parent directory");
        fs.validate();

        FileSystem full = new FileSystem(new VirtualDisk(temporary.resolve("move-full.img"))); full.format();
        full.createDirectory("/dst"); full.createFile("/src", 4, new byte[]{7});
        for (int i = 0; i < 8; i++) full.createFile("/dst/f" + i, 4, new byte[]{(byte) i});
        full.createFile("/pad", 4, new byte[(251 - full.usedBlocks()) * 64]);
        byte[] saved = Files.readAllBytes(full.disk().file());
        rejects(() -> full.move("/src", "/dst"), "Full target directory cannot expand on full disk");
        check(Arrays.equals(saved, Files.readAllBytes(full.disk().file())), "Failed move rolls back directory and FAT");
        check(full.require("/src").length() == 1, "Source survives failed move"); full.validate();
    }
    private static void partitions() throws Exception {
        Kernel k = kernel();
        ok(k, "create /sav keep-main"); byte[] original = k.fs().readAll(k.fs().require("/sav"));
        ok(k, "fdisk create data 64");
        check(k.disk().blockCount() == 192 && k.disk().firstBlock() == 0, "Main shrinks within physical disk");
        check(Files.size(k.disk().file()) == 16384, "Physical disk stays exactly 256 blocks");
        check(ok(k, "fdk list").contains("192-255"), "Alias displays real partition extent");
        ok(k, "create /aa/run.e x=9|end"); ok(k, "run /aa/run.e"); bad(k, "fdisk use data");
        for (int i = 0; i < 10; i++) k.cpu().cycle();
        ok(k, "chdir /aa"); ok(k, "fdisk use DATA");
        check(k.location().equals("data:/") && k.disk().firstBlock() == 192, "Partition switch resets working directory");
        bad(k, "type /sav"); ok(k, "mkdir /aa"); ok(k, "create /aa/txt isolated");
        Kernel restarted = new Kernel(k.disk().file()); restarted.boot(42); restarted.setAutoArrivals(false);
        check(restarted.location().equals("data:/"), "Active partition persists across restart");
        check(ok(restarted, "type /aa/txt").contains("isolated"), "Partition content persists");
        ok(restarted, "format"); bad(restarted, "type /aa/txt");
        ok(restarted, "fdisk use main");
        check(Arrays.equals(original, restarted.fs().readAll(restarted.fs().require("/sav"))), "Formatting data leaves main intact");
        bad(restarted, "fdisk delete main"); ok(restarted, "fdisk delete data"); bad(restarted, "fdisk use data");
        ok(restarted, "fdisk create tiny 6"); ok(restarted, "fdisk use tiny");
        ok(restarted, "create /one " + "x".repeat(64));
        byte[] full = Files.readAllBytes(restarted.disk().file()); bad(restarted, "create /two y");
        check(Arrays.equals(full, Files.readAllBytes(restarted.disk().file())), "Partition full rollback preserves whole disk");
        rejects(() -> restarted.disk().readBlock(6), "Cannot access next partition through logical block number");
        restarted.fs().validate(); ok(restarted, "fdisk delete main");
        ok(restarted, "fdisk create other 32"); ok(restarted, "fdisk use other");
        check(restarted.disk().firstBlock() == 0 && restarted.disk().blockCount() == 32, "Deleted extents can be reused");
        bad(restarted, "fdisk create bad 5"); bad(restarted, "fdisk create bad 257");
        bad(restarted, "fdisk create other 32"); bad(restarted, "fdisk create ../evil 6");
        bad(restarted, "fdisk use absent"); bad(restarted, "fdisk create x nope"); bad(restarted, "fdisk delete");
        check(Kernel.isDestructiveCommand("fdisk delete main") && Kernel.isDestructiveCommand("FDK DELETE main"), "Partition deletion asks for confirmation");
        check(!Kernel.isDestructiveCommand("fdisk create data 64") && !Kernel.isDestructiveCommand("deldir .."), "Safe and relative commands do not throw in confirmation check");
        restarted.fs().validate();
    }
    private static void partitionFailures() throws Exception {
        Kernel k = kernel(); ok(k, "create /big " + "x".repeat(2000));
        byte[] before = Files.readAllBytes(k.disk().file());
        bad(k, "fdisk create huge 230");
        check(Arrays.equals(before, Files.readAllBytes(k.disk().file())) && k.disk().blockCount() == 256, "Used tail cannot be partitioned");
        check(!Files.exists(PartitionManager.tablePath(k.disk().file())), "Failure does not introduce partition table");

        Kernel brokenTarget = kernel(); ok(brokenTarget, "fdisk create data 64");
        Path targetTable = PartitionManager.tablePath(brokenTarget.disk().file());
        byte[] originalTable = Files.readAllBytes(targetTable);
        byte[] brokenImage = Files.readAllBytes(brokenTarget.disk().file());
        brokenImage[192 * 64] = 0; // 破坏非当前分区的系统FAT标记。
        Files.write(brokenTarget.disk().file(), brokenImage);
        bad(brokenTarget, "fdisk use data");
        check(brokenTarget.location().equals("main:/") && brokenTarget.disk().blockCount() == 192, "Failed switch keeps current partition");
        check(Arrays.equals(originalTable, Files.readAllBytes(targetTable))
                && Arrays.equals(brokenImage, Files.readAllBytes(brokenTarget.disk().file())), "Failed switch preserves table and disk");
        brokenTarget.fs().validate();

        // 注入新分区初始化的写入故障，确保物理磁盘、视图与FAT一起恢复。
        VirtualDisk disk = new VirtualDisk(temporary.resolve("write-fault.img")) {
            boolean failed;
            @Override public void writeBlock(int block, byte[] data) {
                if (firstBlock() > 0 && !failed) { failed = true; throw new IllegalStateException("injected write failure"); }
                super.writeBlock(block, data);
            }
        };
        FileSystem fs = new FileSystem(disk); fs.format(); fs.createFile("/a", 4, new byte[]{42});
        PartitionManager manager = new PartitionManager(disk, fs); manager.load();
        byte[] saved = Files.readAllBytes(disk.file());
        rejects(() -> manager.create("data", 64), "Injected partition write failure");
        check(Arrays.equals(saved, Files.readAllBytes(disk.file())) && disk.blockCount() == 256, "Failed create restores original disk and view"); fs.validate();

        // 分区表写入失败（目标是非空目录）不能留下半次划分。
        Path table = PartitionManager.tablePath(disk.file()); Files.createDirectory(table); Files.writeString(table.resolve("block"), "occupied");
        rejects(() -> manager.create("data", 64), "Partition table cannot be saved");
        check(Arrays.equals(saved, Files.readAllBytes(disk.file())) && fs.readAll(fs.require("/a"))[0] == 42, "Metadata failure rolls back data");

        Kernel corrupt = kernel(); ok(corrupt, "fdisk create data 64");
        Path metadata = PartitionManager.tablePath(corrupt.disk().file()); Properties properties = new Properties();
        try (var input = Files.newInputStream(metadata)) { properties.load(input); }
        properties.setProperty("partition.1", "data,0,64");
        try (var output = Files.newOutputStream(metadata)) { properties.store(output, "overlap"); }
        byte[] image = Files.readAllBytes(corrupt.disk().file()), invalidTable = Files.readAllBytes(metadata);
        rejects(() -> new Kernel(corrupt.disk().file()).boot(42), "Overlapping partition table rejected");
        check(Arrays.equals(image, Files.readAllBytes(corrupt.disk().file())) && Arrays.equals(invalidTable, Files.readAllBytes(metadata)), "Bad metadata is never silently reformatted");
    }
    private static void recovery() throws Exception {
        Kernel k = kernel(); ok(k, "fdisk create data 64"); ok(k, "fdisk use data"); ok(k, "create /sav retained-in-backup");
        byte[] image = Files.readAllBytes(k.disk().file());
        byte[] table = Files.readAllBytes(PartitionManager.tablePath(k.disk().file()));
        SafeBoot.Recovery recovered = SafeBoot.recover(k.disk().file(), Instant.parse("2026-11-22T00:00:00Z"));
        check(Arrays.equals(image, Files.readAllBytes(recovered.backup())), "Recovery preserves whole partitioned disk");
        check(Arrays.equals(table, Files.readAllBytes(PartitionManager.tablePath(recovered.backup()))), "Recovery preserves matching partition table");
        check(recovered.kernel().location().equals("main:/") && recovered.kernel().disk().blockCount() == 256, "Recovery creates standalone default disk");
        recovered.kernel().fs().validate();
        Kernel backup = new Kernel(recovered.backup()); backup.boot(42);
        check(ok(backup, "type /sav").contains("retained-in-backup"), "Recovered backup can be mounted with its table");

        Kernel missingImage = kernel(); ok(missingImage, "fdisk create data 64");
        Path orphanedTable = PartitionManager.tablePath(missingImage.disk().file());
        byte[] orphanedBytes = Files.readAllBytes(orphanedTable);
        Files.delete(missingImage.disk().file());
        rejects(() -> new Kernel(missingImage.disk().file()).boot(42), "Orphaned partition table requires explicit recovery");
        SafeBoot.Recovery fresh = SafeBoot.recover(missingImage.disk().file(), Instant.parse("2026-11-22T00:00:00Z"));
        check(Files.exists(fresh.backup()) && Arrays.equals(orphanedBytes, Files.readAllBytes(fresh.backup())), "Missing-image recovery backs up surviving metadata");
        check(!Files.exists(orphanedTable) && fresh.kernel().location().equals("main:/"), "Missing-image recovery creates usable standalone disk");
        fresh.kernel().fs().validate();
    }
}
