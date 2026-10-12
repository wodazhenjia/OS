package cn.edu.scau.os;

import cn.edu.scau.os.cpu.InterruptPSW;
import cn.edu.scau.os.disk.DiskLayout;
import cn.edu.scau.os.disk.FileEntry;
import cn.edu.scau.os.disk.FileSystem;
import cn.edu.scau.os.disk.VirtualDisk;
import cn.edu.scau.os.instruction.Assembler;
import cn.edu.scau.os.instruction.Opcode;
import cn.edu.scau.os.kernel.Kernel;
import cn.edu.scau.os.process.BlockReason;
import cn.edu.scau.os.process.ProcessState;
import cn.edu.scau.os.storage.MemoryManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 无外部测试依赖；所有模拟磁盘都在临时目录内，失败以非零状态退出。 */
public final class RequirementsRegressionTest {
    private static Path temporary;
    private static int assertions;
    private static int fixture;

    public static void main(String[] args) throws Exception {
        temporary = Files.createTempDirectory("simos-regression-");
        instructions();
        diskAndFiles();
        persistenceAndMigration();
        memory();
        scheduling();
        devices();
        initializationAndFailures();
        logsAndArrivals();
        concurrentEditor();
        System.out.println("Requirements regression passed: " + assertions + " assertions, 9 groups");
    }
    private static void check(boolean result, String message) {
        assertions++;
        if (!result) throw new AssertionError(message);
    }
    private static void rejects(Runnable operation, String message) {
        assertions++;
        try { operation.run(); }
        catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }
    private static Kernel kernel() {
        Kernel k = new Kernel(temporary.resolve("kernel-" + fixture++ + ".img"));
        k.initialize(42); k.setAutoArrivals(false); return k;
    }
    private static FileSystem filesystem() {
        FileSystem fs = new FileSystem(new VirtualDisk(temporary.resolve("disk-" + fixture++ + ".img")));
        fs.format(); return fs;
    }
    private static void tick(Kernel k, int n) { for (int i = 0; i < n; i++) k.cpu().cycle(); }
    private static void drain(Kernel k) {
        for (int i = 0; i < 1000 && k.scheduler().freeQueue().size() != 9; i++) {
            k.cpu().cycle(); invariant(k);
        }
        check(k.scheduler().freeQueue().size() == 9, "All user processes finish");
        check(k.memory().freeBytes() == 512, "All memory returned");
    }
    private static void invariant(Kernel k) {
        Set<Integer> queues = new HashSet<>(k.scheduler().readyQueue());
        check(queues.size() == k.scheduler().readyQueue().size(), "No duplicate ready entries");
        for (int pid : k.scheduler().blockedQueue()) check(queues.add(pid), "Ready/blocked disjoint");
        check(!queues.contains(k.scheduler().runningPid()), "Running process not queued");
        int total = k.memory().partitions().stream().mapToInt(p -> p.size).sum();
        check(total == 512, "Partition sum is 512");
        for (var p : k.scheduler().pcbs()) {
            if (p.pid == 0) continue;
            if (p.state == ProcessState.READY) check(k.scheduler().isReady(p.pid), "READY is queued");
            if (p.state == ProcessState.BLOCKED) check(k.scheduler().isBlocked(p.pid), "BLOCKED is queued");
            if (p.state == ProcessState.FREE) check(k.memory().partitionOf(p.pid) == null, "FREE has no memory");
        }
        for (var d : k.devices().devices()) {
            if (!d.isFree()) check(k.scheduler().pcb(d.owner).state == ProcessState.BLOCKED, "Device owner remains blocked");
        }
    }
    private static void instructions() {
        for (int value = 0; value <= 99; value++) {
            var assembled = Assembler.assemble("x=" + value);
            check(assembled.ok() && assembled.code().length == 1, "Single byte assignment");
            check(Assembler.decode(assembled.code(), 0).operand() == value, "Assignment operand");
        }
        for (String type : List.of("A", "B", "C")) for (int duration = 0; duration <= 9; duration++) {
            var a = Assembler.assemble("!" + type + duration);
            check(a.ok() && a.code().length == 1, "Single byte I/O");
            var decoded = Assembler.decode(a.code(), 0);
            check(decoded.deviceName().equals(type) && decoded.operand() == duration && decoded.length() == 1, "I/O round trip");
        }
        check(!Assembler.assemble("x=100").ok() && !Assembler.assemble("!A10").ok(), "Operand bounds");
        check(!Assembler.assemble("!A09").ok(), "Device operand has one digit");
        check(!Opcode.isLegalSingleByte(0x8A) && !Opcode.isLegalSingleByte(0xFF), "Reserved instructions invalid");
        check(Assembler.assemble("x\t=\t3\n!A\t2\nend").ok(), "Whitespace accepted");
    }
    private static void diskAndFiles() throws Exception {
        FileSystem fs = filesystem();
        byte[] full = new byte[251 * 64]; Arrays.fill(full, (byte) 42);
        fs.createFile("/all", DiskLayout.ATTR_NORMAL, full);
        check(fs.chain(fs.require("/all").startBlock()).size() == 251, "Use blocks 253,254,255");
        check(Arrays.equals(full, fs.readAll(fs.require("/all"))), "Full disk round trip");
        byte[] saved = Files.readAllBytes(fs.disk().file());
        rejects(() -> fs.createFile("/new", 4, new byte[1]), "Disk full rejected");
        check(Arrays.equals(saved, Files.readAllBytes(fs.disk().file())), "Disk full rollback");
        fs.validate(); fs.deleteFile("/all");
        check(fs.usedBlocks() == 0, "Full disk recovery");
        fs.createFile("/b64", 4, new byte[64]); fs.createFile("/b65", 4, new byte[65]);
        check(fs.chain(fs.require("/b64").startBlock()).size() == 1 && fs.chain(fs.require("/b65").startBlock()).size() == 2, "64/65 boundary");
        for (int i = 0; i < 6; i++) fs.createDirectory("/d" + i);
        saved = Files.readAllBytes(fs.disk().file());
        rejects(() -> fs.createDirectory("/ful"), "Root ninth entry rejected");
        check(Arrays.equals(saved, Files.readAllBytes(fs.disk().file())), "Root full rollback");
        fs.createFile("/d0/abc", 4, new byte[]{1});
        fs.createFile("/d0/abc.e", 4, new byte[]{2});
        fs.changeAttributes("/d0/abc.e", 1);
        check(!fs.require("/d0/abc").isReadonly() && fs.require("/d0/abc.e").isReadonly(), "Exact extension match");
        rejects(() -> fs.writeFile(fs.requireDir("/d0"), "abc", "e", 4, new byte[0]), "Readonly write denied");
        rejects(() -> fs.deleteFile("/d0/abc.e"), "Readonly deletion denied");
        saved = Files.readAllBytes(fs.disk().file());
        rejects(() -> fs.removeDirectoryRecursively("/d0"), "Recursive readonly rejection");
        check(Arrays.equals(saved, Files.readAllBytes(fs.disk().file())), "Recursive deletion rolled back");
        fs.changeAttributes("/d0", 1);
        check(fs.require("/d0").isDirectory(), "Directory attribute preserved");
        rejects(() -> fs.writeFile(4, "d0", "", 4, new byte[0]), "Directory overwrite denied");
        rejects(() -> fs.createFile("/d1/中文", 4, new byte[0]), "ASCII names enforced");
        check(FileSystem.normalize("\\d1\\..\\d0\\abc").equals("/d0/abc"), "Parent path normalization");
        fs.validate();
        FaultDisk faultDisk = new FaultDisk(temporary.resolve("fault.img"));
        FileSystem faultFs = new FileSystem(faultDisk); faultFs.format();
        byte[] beforeFailure = Files.readAllBytes(faultDisk.file());
        faultDisk.failAfter = 2;
        rejects(() -> faultFs.createFile("/new", 4, new byte[65]), "Injected partial I/O failure rejected");
        check(Arrays.equals(beforeFailure, Files.readAllBytes(faultDisk.file())) && faultFs.usedBlocks() == 0,
                "Partial I/O rollback restores both FAT and disk");
        faultFs.createFile("/new", 4, new byte[65]); faultFs.validate();
        check(faultFs.require("/new").length() == 65, "Filesystem usable after I/O rollback");
    }
    private static final class FaultDisk extends VirtualDisk {
        int failAfter = -1;
        FaultDisk(Path path) { super(path); }
        @Override public void writeBlock(int block, byte[] data) {
            super.writeBlock(block, data);
            if (--failAfter == 0) throw new IllegalStateException("Injected partial disk write failure");
        }
    }
    private static void persistenceAndMigration() throws Exception {
        Kernel first = kernel();
        check(first.exec("create /doc/uni 中文测试 | 保留分隔符").ok(), "UTF8 create");
        first.assembleAndSave("/aa/new.e", "x=99\n!C9\nend");
        Kernel second = new Kernel(first.disk().file()); second.boot(42);
        check(second.exec("type /doc/uni").message().contains("中文测试 | 保留分隔符"), "UTF8 persistence");
        check(second.exec("type /aa/new.e").message().contains("!C9"), "Executable persistence");
        second.fs().validate();

        Path legacy = temporary.resolve("legacy.img");
        VirtualDisk disk = new VirtualDisk(legacy); disk.clear();
        byte[] fat = new byte[256]; Arrays.fill(fat, 0, 5, (byte) 253); fat[5] = (byte) 255;
        for (int b = 0; b < 4; b++) disk.writeBlock(b, Arrays.copyOfRange(fat, b * 64, (b + 1) * 64));
        byte[] root = new byte[64];
        System.arraycopy(FileEntry.pack("old", "e", 1, 5, 6), 0, root, 0, 8); disk.writeBlock(4, root);
        byte[] code = new byte[64]; byte[] original = {5,64,66,3,65,69}; System.arraycopy(original,0,code,0,6); disk.writeBlock(5,code);
        FileSystem migrated = new FileSystem(new VirtualDisk(legacy)); migrated.load();
        check(migrated.require("/old.e").isReadonly(), "Legacy readonly preserved");
        check(Arrays.equals(migrated.readAll(migrated.require("/old.e")), Assembler.assemble("x=5\nx++\n!A3\nx--\nend").code()), "Legacy bytecode migrated");
        try (var backups = Files.list(temporary)) { check(backups.anyMatch(p -> p.getFileName().toString().startsWith("disk-legacy-")), "Legacy backup retained"); }
        migrated.validate();
        FileSystem reloaded = new FileSystem(new VirtualDisk(legacy)); reloaded.load(); reloaded.validate();
        check(reloaded.require("/old.e").length() == 5, "Migration not repeated");
        byte[] corrupt = reloaded.disk().readBlock(0); corrupt[5] = 5; reloaded.disk().writeBlock(0,corrupt);
        byte[] damaged = Files.readAllBytes(legacy);
        rejects(() -> new FileSystem(new VirtualDisk(legacy)).load(), "Cycle rejected on mount");
        check(Arrays.equals(damaged, Files.readAllBytes(legacy)), "Bad disk not formatted");
    }
    private static void memory() {
        MemoryManager m = new MemoryManager();
        m.allocate(1, 200); m.allocate(2, 100); m.allocate(3, 100); m.allocate(4, 112);
        m.load(1, new byte[200]);
        rejects(() -> m.readByte(1, 200), "Protected read boundary");
        check(!m.checkAccess(1, 0, 0) && !m.checkAccess(1, 0, Integer.MAX_VALUE), "Invalid range rejected");
        m.free(1); m.free(3); m.setFit(MemoryManager.Fit.BEST);
        check(m.allocate(5,80).start == 300, "Best fit chooses smallest hole");
        m.reset(); m.setFit(MemoryManager.Fit.NEXT);
        m.allocate(1,200); m.allocate(2,100); m.allocate(3,100); m.free(1); m.free(2);
        check(m.allocate(4,80).start == 400, "Next fit survives hole merge");
        m.reset(); m.setFit(MemoryManager.Fit.FIRST);
        check(m.allocate(1,3).start == 0, "First fit");
        m.load(1,new byte[]{7,8,9}); check(m.readByte(1,2) == 9, "Actual user memory");
        m.partitions().get(0).size = 999;
        check(m.partitionOf(1).size == 3, "Snapshot detached");
        m.free(1); check(m.freeBytes() == 512 && m.partitions().size() == 1, "Memory merge");
    }
    private static void scheduling() {
        Kernel k = kernel();
        k.assembleAndSave("/aa/one.e", "x=10\n" + "x++\n".repeat(10) + "end");
        k.assembleAndSave("/aa/two.e", "x=20\nx++\nx++\nend");
        int one=k.create("/aa/one.e"), two=k.create("/aa/two.e");
        tick(k,6);
        check(k.scheduler().pcb(one).pc == 6 && k.scheduler().pcb(one).timeSliceLeft == 0, "Exactly six instructions per slice");
        check((k.scheduler().pcb(one).psw & InterruptPSW.BIT_SCHEDULE) != 0, "Clock interrupt raised");
        tick(k,1);
        check(k.scheduler().runningPid() == two && k.scheduler().pcb(two).pc == 1 && k.scheduler().pcb(two).timeSliceLeft == 5, "Round robin next process executes");
        drain(k);
        check(k.results().stream().anyMatch(s -> s.contains("one.e") && s.contains("x = 20")), "Context restored for first process");
        check(k.results().stream().anyMatch(s -> s.contains("two.e") && s.contains("x = 22")), "Separate AX registers");
        k.cpu().step(); check(k.cpu().isPaused(), "Single step stays paused");

        Kernel protectedKernel = kernel();
        int p = protectedKernel.create("/lo.e"); protectedKernel.onSchedule();
        protectedKernel.scheduler().pcb(p).pc = 5;
        protectedKernel.cpu().restoreContext(protectedKernel.scheduler().pcb(p));
        tick(protectedKernel,2);
        check(protectedKernel.results().stream().anyMatch(s -> s.contains("存储保护")), "CPU access invokes storage protection");
        check(protectedKernel.results().stream().noneMatch(s -> s.contains("执行结束")), "Fault is not reported as success");
    }
    private static void devices() {
        Kernel k=kernel();
        check(k.snapshot().rootChildren().stream().noneMatch(n -> n.name().equals("/")), "Snapshot excludes duplicate root");
        long readOps = k.disk().readOps(); k.snapshot(); k.executableFiles();
        check(k.disk().readOps() == readOps, "Unchanged directory snapshot cached");
        k.assembleAndSave("/aa/one.e", "!A5\nx=11\nend");
        k.assembleAndSave("/aa/two.e", "!A1\nx=22\nend");
        int one=k.create("/aa/one.e"), two=k.create("/aa/two.e");
        tick(k,1);
        check(k.scheduler().pcb(two).pc == 0 && k.scheduler().pcb(two).timeSliceLeft == 6, "No time charged after I/O switch");
        check(k.devices().deviceOf(one).remaining == 5, "New I/O not decremented in request tick");
        tick(k,2);
        check(k.scheduler().pcb(one).state == ProcessState.BLOCKED && k.devices().deviceOf(one).remaining == 3, "Longer I/O remains blocked");
        check(k.scheduler().pcb(two).state != ProcessState.BLOCKED, "Actual completed owner awakened");
        drain(k);

        Kernel saturated=kernel();
        saturated.assembleAndSave("/aa/io.e", "!A9\nx=7\nend");
        int a=saturated.create("/aa/io.e"), b=saturated.create("/aa/io.e"), c=saturated.create("/aa/io.e");
        tick(saturated,3);
        check(saturated.devices().waitQueue("A").equals(List.of(c)), "Only unassigned processes queued");
        tick(saturated,7);
        check(saturated.devices().deviceOf(c) != null && saturated.devices().deviceOf(c).remaining == 9, "FIFO waiter receives device immediately");
        check(saturated.scheduler().pcb(c).state == ProcessState.BLOCKED, "Waiter does not run before own I/O completes");
        check(saturated.scheduler().pcb(c).pc == 1, "Queued request executed once");
        drain(saturated);
        check(saturated.results().stream().filter(s -> s.contains("x = 7")).count() == 3, "All contending processes finish correctly");

        Kernel killed=kernel(); killed.assembleAndSave("/aa/io.e", "!A9\nend");
        int first=killed.create("/aa/io.e"); killed.create("/aa/io.e"); int wait=killed.create("/aa/io.e"); tick(killed,3);
        killed.destroy(first);
        check(killed.devices().deviceOf(wait)!=null, "Termination hands device to waiter"); drain(killed);
    }
    private static void initializationAndFailures() {
        Kernel k=kernel(); k.create("/lo.e"); tick(k,2); k.initialize(42); k.setAutoArrivals(false);
        check(k.memory().freeBytes()==512 && k.scheduler().readyQueue().isEmpty() && k.scheduler().blockedQueue().isEmpty(), "Reset clears memory and queues");
        check(k.systemClock()==0 && k.cpu().tick()==0 && k.scheduler().freeQueue().size()==9, "Reset clocks and PCB pool");
        check(k.devices().devices().stream().allMatch(d -> d.isFree()) && k.devices().waitQueue("A").isEmpty(), "Reset devices");
        k.exec("create /aa/emp.e"); int free=k.scheduler().freeQueue().size();
        check(k.create("/aa/emp.e")==-1 && k.scheduler().freeQueue().size()==free, "Empty executable doesn't leak PCB");
        check(k.create("/doc/rme")==-1 && k.scheduler().freeQueue().size()==free, "Text not executable");
        k.assembleAndSave("/aa/max.e", "x++\n".repeat(511)+"end");
        k.assembleAndSave("/aa/big.e", "x++\n".repeat(512)+"end");
        check(k.create("/aa/big.e")==-1 && k.scheduler().freeQueue().size()==free, "Impossible memory demand rejected");
        int full=k.create("/aa/max.e"), waiting=k.create("/lo.e");
        check(k.scheduler().pcb(waiting).blockReason==BlockReason.MEMORY, "Insufficient memory blocks");
        k.destroy(full);
        check(k.scheduler().pcb(waiting).state==ProcessState.READY && k.memory().readByte(waiting,0)==1, "Memory freed then loaded and awakened");
        drain(k);
    }
    private static void logsAndArrivals() {
        Kernel k=kernel();
        for (int i=0;i<500;i++) k.log("entry-"+i);
        var entries=k.logEntriesAfter(0); check(entries.size()==400,"Bounded log");
        long last=entries.get(entries.size()-1).sequence(); k.log("after-limit");
        check(k.logEntriesAfter(last).size()==1,"Logs continue after retention limit");
        k.initialize(42); check(k.logEntriesAfter(last).size()>0,"Reset does not reuse log sequence");
        Kernel a=kernel(), b=kernel(); a.setAutoArrivals(true); b.setAutoArrivals(true);
        Set<Integer> intervals=new HashSet<>(); int previous=-1; long seq=0;
        for(int i=0;i<150;i++) {
            a.cpu().cycle(); b.cpu().cycle(); invariant(a);
            for(var entry:a.logEntriesAfter(seq)) {
                if(entry.text().contains("创建进程P")) { if(previous>=0) intervals.add(i-previous); previous=i; }
                seq=entry.sequence();
            }
        }
        check(intervals.size()>1,"Arrival intervals are random");
        check(a.snapshot().runningInfo().equals(b.snapshot().runningInfo()) && a.results().equals(b.results()),"Fixed seed reproducibility");
    }
    private static void concurrentEditor() throws Exception {
        Kernel k=kernel(); k.setAutoArrivals(true); k.start(20);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var save=pool.submit(() -> { for(int i=0;i<25;i++) { k.assembleAndSave("/aa/new.e","x="+i+"\n!B1\nend"); k.create("/aa/new.e"); } });
            var view=pool.submit(() -> { for(int i=0;i<100;i++) k.snapshot(); });
            save.get(15,java.util.concurrent.TimeUnit.SECONDS); view.get(15,java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); k.shutdown(); }
        synchronized(k.lock()) { k.fs().validate(); invariant(k); }
        check(!k.cpu().isRunning(),"CPU shuts down after concurrent edits");
    }
}
