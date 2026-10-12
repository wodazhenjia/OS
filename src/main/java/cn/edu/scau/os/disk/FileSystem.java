package cn.edu.scau.os.disk;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.file.Path;
import java.util.function.Supplier;
import cn.edu.scau.os.instruction.Opcode;

/**
 * 模拟磁盘文件系统：文件分配表（FAT，显式链接）+ 树型目录 + 磁盘空间分配回收。
 *
 * <p>指导书要点对应关系：</p>
 * <ul>
 *   <li>文件逻辑结构：流式结构（本类按字节流读写）；</li>
 *   <li>文件物理结构：显式链接（FAT 记录下一块号）；</li>
 *   <li>磁盘空间管理：FAT 项的值 0 表示空闲，非 0 表示已分配（下一块号或结束标志）；</li>
 *   <li>目录结构：树型，根目录固定在盘块 {@link DiskLayout#ROOT_BLOCK}，子目录位置与大小不固定；</li>
 *   <li>属性与保护：属性字节位定义见 {@link DiskLayout}，只读文件禁止写/删。</li>
 * </ul>
 */
public class FileSystem {

    private final VirtualDisk disk;
    /** 内存中的 FAT 镜像，长度为 FAT 表项数。 */
    private final byte[] fat = new byte[DiskLayout.FAT_ENTRIES];
    private long revisions;
    private int transactionDepth;

    /** 同一命令中的目录/FAT/数据更新共同提交；异常时恢复整个文件副本。 */
    private <T> T transaction(Supplier<T> operation) {
        if (transactionDepth > 0) return operation.get();
        Path backup = disk.backup("simos-undo-");
        byte[] savedFat = fat.clone();
        long savedRevision = revisions;
        boolean discard = true;
        transactionDepth++;
        try {
            T value = operation.get();
            disk.flushDirty();
            return value;
        } catch (RuntimeException e) {
            try { disk.restore(backup); }
            catch (RuntimeException restoreError) { discard = false; e.addSuppressed(restoreError); throw e; }
            System.arraycopy(savedFat, 0, fat, 0, fat.length);
            revisions = savedRevision;
            throw e;
        } finally {
            transactionDepth--;
            if (discard) disk.discardBackup(backup);
        }
    }

    /** 加载磁盘，识别并迁移旧版FAT和双字节设备指令。迁移前永久保留原盘副本。 */
    public void load() {
        disk.validateSize();
        for (int i = 0; i < DiskLayout.FAT_BLOCKS; i++) {
            System.arraycopy(disk.readBlock(i), 0, fat, i * VirtualDisk.BLOCK_SIZE, VirtualDisk.BLOCK_SIZE);
        }
        boolean legacy = fatAt(0) == 253;
        Path legacyBackup = legacy ? disk.backup("disk-legacy-") : null;
        try {
            if (legacy) {
                for (int i = 0; i < fat.length; i++) {
                    fat[i] = (byte) switch (fatAt(i)) {
                        case 253 -> DiskLayout.FAT_RESERVED;
                        case 254 -> DiskLayout.FAT_BAD;
                        case 255 -> DiskLayout.FAT_EOC;
                        default -> fatAt(i);
                    };
                }
            }
            validate();
            if (legacy) {
                transaction(() -> { flushFat(); migrateExecutables(DiskLayout.ROOT_BLOCK); return null; });
                validate();
            }
            bump();
        } catch (RuntimeException e) {
            if (legacyBackup != null) disk.restore(legacyBackup);
            throw new IllegalStateException("磁盘加载失败，未格式化；请检查磁盘或恢复备份：" + disk.file(), e);
        }
    }

    private void migrateExecutables(int directory) {
        for (FileEntry entry : list(directory)) {
            if (entry.isDirectory()) { migrateExecutables(entry.startBlock()); continue; }
            if (!entry.isExecutable()) continue;
            byte[] old = readAll(entry);
            java.io.ByteArrayOutputStream converted = new java.io.ByteArrayOutputStream();
            for (int pc = 0; pc < old.length; pc++) {
                int b = old[pc] & 255;
                if (b <= 63) converted.write(b);
                else if (b == 64) converted.write(Opcode.OP_INC);
                else if (b == 65) converted.write(Opcode.OP_DEC);
                else if (b == 69) converted.write(Opcode.OP_END);
                else if (b >= 66 && b <= 68 && pc + 1 < old.length) {
                    int duration = old[++pc] & 255;
                    if (duration > 9) throw new IllegalStateException("旧设备时间超出0~9，保留原盘，请手工修改：" + entry.displayName());
                    converted.write((Opcode.OP_DEV_A + ((b - 66) << 4)) | duration);
                } else throw new IllegalStateException("无法迁移旧指令：" + entry.displayName());
            }
            int attributes = entry.attributes();
            updateEntry(directory, entry.displayName(), attributes & ~DiskLayout.ATTR_READONLY, entry.startBlock(), entry.length());
            writeFile(directory, entry.name(), entry.extension(), attributes, converted.toByteArray());
            FileEntry updated = findInDirectory(directory, entry.displayName());
            updateEntry(directory, entry.displayName(), attributes, updated.startBlock(), updated.length());
        }
    }

    /** 检测循环链、共享盘块、非法目录项、短链与泄漏盘块，拒绝带病加载。 */
    public void validate() {
        for (int i = 0; i <= DiskLayout.ROOT_BLOCK; i++) {
            if (fatAt(i) != DiskLayout.FAT_RESERVED) throw new IllegalStateException("系统盘块标记错误：" + i);
        }
        boolean[] visited = new boolean[fat.length];
        validateDirectory(DiskLayout.ROOT_BLOCK, visited);
        for (int i = DiskLayout.FIRST_DATA_BLOCK; i < fat.length; i++) {
            if (!visited[i] && fatAt(i) != DiskLayout.FAT_FREE && fatAt(i) != DiskLayout.FAT_BAD)
                throw new IllegalStateException("存在未被目录引用的盘块：" + i);
        }
    }
    private void validateDirectory(int directory, boolean[] visited) {
        claimChain(directory, visited);
        java.util.Set<String> names = new java.util.HashSet<>();
        for (FileEntry e : list(directory)) {
            checkName(e.name(), e.extension());
            if (!names.add(e.displayName().toLowerCase(java.util.Locale.ROOT))) throw new IllegalStateException("重复目录项");
            if (e.isDirectory()) {
                if (!e.extension().isEmpty()) throw new IllegalStateException("目录不得有扩展名");
                validateDirectory(e.startBlock(), visited);
            } else {
                List<Integer> blocks = claimChain(e.startBlock(), visited);
                if (blocks.size() != Math.max(1, (e.length() + 63) / 64)) throw new IllegalStateException("文件长度与FAT链不匹配：" + e.displayName());
            }
        }
    }
    private List<Integer> claimChain(int start, boolean[] visited) {
        List<Integer> blocks = chain(start);
        for (int b : blocks) {
            if (visited[b] || (b < DiskLayout.FIRST_DATA_BLOCK && b != DiskLayout.ROOT_BLOCK))
                throw new IllegalStateException("盘块共享或目录成环：" + b);
            visited[b] = true;
        }
        return blocks;
    }

    public FileSystem(VirtualDisk disk) {
        this.disk = disk;
    }

    public VirtualDisk disk() {
        return disk;
    }

    /** 文件系统修改计数，界面轮询该值判断是否需要重画磁盘视图。 */
    public long revisions() {
        return revisions;
    }

    private void bump() {
        revisions++;
        // 文件系统发生修改：把攒下的脏盘块落盘，保证 disk 文件始终反映最新内容
        if (transactionDepth == 0) disk.flushDirty();
    }

    // ==================================================================
    // 格式化：建立 FAT 区与根目录
    // ==================================================================

    /** 初始化磁盘：FAT 全部置空闲，系统区标记为保留，根目录 8 个空目录项。 */
    public void format() {
        disk.clear();
        Arrays.fill(fat, (byte) DiskLayout.FAT_FREE);
        // FAT 自身占用的块与根目录块标记为"系统保留"
        for (int i = DiskLayout.FAT_START_BLOCK; i < DiskLayout.FAT_START_BLOCK + DiskLayout.FAT_BLOCKS; i++) {
            fat[i] = (byte) DiskLayout.FAT_RESERVED;
        }
        fat[DiskLayout.ROOT_BLOCK] = (byte) DiskLayout.FAT_RESERVED;
        flushFat();

        byte[] root = new byte[VirtualDisk.BLOCK_SIZE];
        disk.writeBlock(DiskLayout.ROOT_BLOCK, root);
        bump();
    }

    // ==================================================================
    // FAT 操作
    // ==================================================================

    public int fatAt(int block) {
        return fat[block] & 0xFF;
    }

    public boolean isFree(int block) {
        return fatAt(block) == DiskLayout.FAT_FREE;
    }

    /** 把 FAT 写回磁盘的 FAT 区（每项 1 字节，连续存放）。 */
    private void flushFat() {
        for (int i = 0; i < DiskLayout.FAT_BLOCKS; i++) {
            byte[] blk = new byte[VirtualDisk.BLOCK_SIZE];
            System.arraycopy(fat, i * VirtualDisk.BLOCK_SIZE, blk, 0, VirtualDisk.BLOCK_SIZE);
            disk.writeBlock(DiskLayout.FAT_START_BLOCK + i, blk);
        }
    }

    /**
     * 申请一个空闲盘块。
     *
     * <p>指导书原话："分配一个磁盘块时，不应该从文件分配表第一项查起，因为磁盘中最开始的
     * 几块为系统数据区"，因此扫描从 {@link DiskLayout#FIRST_DATA_BLOCK} 开始。</p>
     */
    public int allocateBlock() {
        for (int i = DiskLayout.FIRST_DATA_BLOCK; i < DiskLayout.FAT_ENTRIES; i++) {
            if (fat[i] == DiskLayout.FAT_FREE) {
                fat[i] = (byte) DiskLayout.FAT_EOC; // 先当作链尾，由调用者按需串联
                flushFat();
                return i;
            }
        }
        throw new IllegalStateException("磁盘空间不足：没有空闲盘块可分配");
    }

    /** 归还一个盘块：把对应 FAT 项改为 0（空闲）。 */
    public void freeBlock(int block) {
        if (block < DiskLayout.FIRST_DATA_BLOCK) {
            return; // 系统区不可回收
        }
        fat[block] = (byte) DiskLayout.FAT_FREE;
        flushFat();
    }

    /** 把 from 块的链指向 to 块。 */
    private void link(int from, int to) {
        fat[from] = (byte) to;
        flushFat();
    }

    /** 从 startBlock 起收集整条链，直到结束标志；带长度保护，防止 FAT 成环时死循环。 */
    public List<Integer> chain(int startBlock) {
        List<Integer> out = new ArrayList<>();
        int cur = startBlock;
        boolean[] seen = new boolean[fat.length];
        while (true) {
            if (cur < DiskLayout.FIRST_DATA_BLOCK && cur != DiskLayout.ROOT_BLOCK || cur >= fat.length || cur < 0 || seen[cur])
                throw new IllegalStateException("FAT链包含非法盘块或循环，起点：" + startBlock);
            seen[cur] = true;
            out.add(cur);
            int next = fatAt(cur);
            if (next == DiskLayout.FAT_EOC || cur == DiskLayout.ROOT_BLOCK && next == DiskLayout.FAT_RESERVED) return out;
            cur = next;
        }
    }

    /** 已分配盘块数（不含系统区）。 */
    public int usedBlocks() {
        int n = 0;
        for (int i = DiskLayout.FIRST_DATA_BLOCK; i < DiskLayout.FAT_ENTRIES; i++) {
            if (fat[i] != DiskLayout.FAT_FREE) {
                n++;
            }
        }
        return n;
    }

    // ==================================================================
    // 目录操作
    // ==================================================================

    /**
     * 解析路径，定位目录项。
     *
     * @param path 路径，如 {@code /aa/bb.e} 或 {@code /aa}；分隔符 {@code /} 与 {@code \} 等价
     * @return 找到的目录项；找不到返回 {@code null}
     */
    public FileEntry resolve(String path) {
        String[] parts = split(path);
        if (parts.length == 0) {
            return null;
        }
        FileEntry cur = findInDirectory(DiskLayout.ROOT_BLOCK, parts[0]);
        if (cur == null) {
            return null;
        }
        for (int i = 1; i < parts.length; i++) {
            if (!cur.isDirectory()) {
                return null; // 中间路径不是目录
            }
            FileEntry next = findInDirectory(cur.startBlock(), parts[i]);
            if (next == null) {
                return null;
            }
            cur = next;
        }
        return cur;
    }

    /**
     * 在某个目录中按名查找，遍历整条目录链。
     *
     * <p>目录项只把名字（3 字节）和扩展名（1 字节）分开存放，所以这里先把传入的
     * 目标名拆成"基名 + 扩展名"再逐项比较：<br>
     * {@code findInDirectory(root, "bad.e")} 会去找名为 {@code bad}、扩展名为 {@code e} 的项；
     * {@code findInDirectory(root, "aa")} 会去找名为 {@code aa} 的项。这样调用方既能传
     * 带扩展名的路径末段，也能只传目录名。</p>
     *
     * @param dirStartBlock 目录起始盘块
     * @param name          目标名，可带扩展名（如 {@code bad.e} 或 {@code aa}）
     * @return 找到的目录项；找不到返回 {@code null}
     */
    public FileEntry findInDirectory(int dirStartBlock, String name) {
        String target = name == null ? "" : name.trim();
        if (target.isEmpty()) {
            return null;
        }
        int dot = target.lastIndexOf('.');
        String baseWanted = (dot > 0 ? target.substring(0, dot) : target).trim();
        String extWanted = dot > 0 ? target.substring(dot + 1).trim() : "";

        for (int block : chain(dirStartBlock)) {
            byte[] data = disk.readBlock(block);
            for (int off = 0; off < VirtualDisk.BLOCK_SIZE; off += DiskLayout.ENTRY_SIZE) {
                if (FileEntry.isEmptySlot(data, off)) {
                    continue;
                }
                FileEntry e = FileEntry.unpack(data, off, off);
                if (!e.name().equalsIgnoreCase(baseWanted)) {
                    continue;
                }
                if (!e.extension().equalsIgnoreCase(extWanted)) {
                    continue;
                }
                return e;
            }
        }
        return null;
    }

    /** 列出目录下的所有有效目录项。 */
    public List<FileEntry> list(int dirStartBlock) {
        List<FileEntry> out = new ArrayList<>();
        for (int block : chain(dirStartBlock)) {
            byte[] data = disk.readBlock(block);
            for (int off = 0; off < VirtualDisk.BLOCK_SIZE; off += DiskLayout.ENTRY_SIZE) {
                if (FileEntry.isEmptySlot(data, off)) {
                    continue;
                }
                out.add(FileEntry.unpack(data, off, off));
            }
        }
        return out;
    }

    /** 列出根目录。 */
    public List<FileEntry> listRoot() {
        return list(DiskLayout.ROOT_BLOCK);
    }

    /**
     * 在目录中登记一个新项。
     *
     * <p>根目录固定 1 块、最多 8 项；子目录通过"再分配一个盘块并挂到目录链上"来扩容
     * （指导书说子目录"位置不固定、大小不固定"，这样处理与之相符）。</p>
     *
     * @return 该项所在盘块号
     */
    public int addEntry(int dirStartBlock, String name, String extension, int attributes, int startBlock, int length) {
        byte[] payload = FileEntry.pack(name, extension, attributes, startBlock, length);
        List<Integer> blocks = chain(dirStartBlock);

        // 1) 先在已有块里找空槽
        for (int block : blocks) {
            byte[] data = disk.readBlock(block);
            for (int off = 0; off < VirtualDisk.BLOCK_SIZE; off += DiskLayout.ENTRY_SIZE) {
                if (FileEntry.isEmptySlot(data, off)) {
                    System.arraycopy(payload, 0, data, off, DiskLayout.ENTRY_SIZE);
                    disk.writeBlock(block, data);
                    bump();
                    return block;
                }
            }
        }
        // 2) 没有空槽：目录扩容（根目录满了会走到这里，由调用者给出错误提示）
        if (dirStartBlock == DiskLayout.ROOT_BLOCK) {
            throw new IllegalStateException("根目录已满（最多 " + DiskLayout.ROOT_MAX_ENTRIES + " 个登记项）");
        }
        int nb = allocateBlock();
        link(blocks.get(blocks.size() - 1), nb);
        byte[] data = new byte[VirtualDisk.BLOCK_SIZE];
        System.arraycopy(payload, 0, data, 0, DiskLayout.ENTRY_SIZE);
        disk.writeBlock(nb, data);
        bump();
        return nb;
    }

    /** 修改某个目录项所在块内的指定槽位（用于更新长度、起始块号、属性等）。 */
    public void updateEntry(int dirStartBlock, String name, int attributes, int startBlock, int length) {
        for (int block : chain(dirStartBlock)) {
            byte[] data = disk.readBlock(block);
            for (int off = 0; off < VirtualDisk.BLOCK_SIZE; off += DiskLayout.ENTRY_SIZE) {
                if (FileEntry.isEmptySlot(data, off)) {
                    continue;
                }
                FileEntry e = FileEntry.unpack(data, off, off);
                if (e.displayName().equalsIgnoreCase(name.trim())) {
                    byte[] packed = FileEntry.pack(e.name(), e.extension(), attributes, startBlock, length);
                    System.arraycopy(packed, 0, data, off, DiskLayout.ENTRY_SIZE);
                    disk.writeBlock(block, data);
                    bump();
                    return;
                }
            }
        }
        throw new IllegalStateException("目录项不存在：" + name);
    }

    /**
     * 查找目录项的父目录起始块。用于删除、更新等需要回写父目录的操作。
     */
    public int parentDirBlock(String path) {
        String[] parts = split(path);
        if (parts.length <= 1) {
            return DiskLayout.ROOT_BLOCK;
        }
        FileEntry cur = findInDirectory(DiskLayout.ROOT_BLOCK, parts[0]);
        if (cur == null || !cur.isDirectory()) {
            throw new IllegalStateException("父目录不存在：" + parts[0]);
        }
        for (int i = 1; i < parts.length - 1; i++) {
            if (!cur.isDirectory()) {
                throw new IllegalStateException("父目录不存在：" + parts[i]);
            }
            cur = findInDirectory(cur.startBlock(), parts[i]);
            if (cur == null || !cur.isDirectory()) {
                throw new IllegalStateException("父目录不存在：" + parts[i]);
            }
        }
        return cur.startBlock();
    }

    /** 目录项名（不含扩展名）。 */
    public static String baseName(String path) {
        String[] parts = split(path);
        if (parts.length == 0) {
            return "";
        }
        String last = parts[parts.length - 1];
        int dot = last.lastIndexOf('.');
        return dot > 0 ? last.substring(0, dot) : last;
    }

    /** 扩展名（不含点）；没有则返回空串。 */
    public static String extensionOf(String path) {
        String[] parts = split(path);
        if (parts.length == 0) {
            return "";
        }
        String last = parts[parts.length - 1];
        int dot = last.lastIndexOf('.');
        return dot > 0 ? last.substring(dot + 1) : "";
    }

    /** 规范化路径：分隔符统一为 {@code /}，去掉首尾多余分隔符。 */
    public static String normalize(String path) {
        if (path == null) throw new IllegalArgumentException("路径不能为空");
        java.util.Deque<String> segments = new java.util.ArrayDeque<>();
        for (String part : path.replace('\\', '/').trim().split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (segments.isEmpty()) throw new IllegalArgumentException("路径超出根目录");
                segments.removeLast();
            } else segments.addLast(part);
        }
        return "/" + String.join("/", segments);
    }

    private static String[] split(String path) {
        String p = normalize(path);
        if (p.equals("/")) {
            return new String[0];
        }
        String body = p.substring(1);
        if (body.isEmpty()) {
            return new String[0];
        }
        String[] parts = body.split("/");
        List<String> out = new ArrayList<>();
        for (String s : parts) {
            String t = s.trim();
            if (!t.isEmpty() && !t.equals(".") && !t.equals("..")) {
                out.add(t);
            }
        }
        return out.toArray(new String[0]);
    }

    // ==================================================================
    // 文件读写（流式结构）
    // ==================================================================

    /** 读取文件的全部字节。 */
    public byte[] readAll(FileEntry e) {
        if (e.isDirectory()) {
            throw new IllegalArgumentException(e.displayName() + " 是目录，不能按文件读取");
        }
        List<Integer> blocks = chain(e.startBlock());
        byte[] out = new byte[Math.max(0, e.length())];
        int written = 0;
        for (int block : blocks) {
            if (written >= out.length) {
                break;
            }
            byte[] data = disk.readBlock(block);
            int n = Math.min(VirtualDisk.BLOCK_SIZE, out.length - written);
            System.arraycopy(data, 0, out, written, n);
            written += n;
        }
        if (written != out.length) throw new IllegalStateException("文件FAT链不完整：" + e.displayName());
        return out;
    }

    /** 读取文件内容为字符串（用于 type 命令）。 */
    public String readText(FileEntry e) {
        return new String(readAll(e), StandardCharsets.UTF_8);
    }

    /**
     * 把字节流写入文件：按需申请盘块并串成 FAT 链。
     *
     * <p>回收规则："文件修改时可能会删除某些内容，造成归还磁盘块，这时是一块一块回收的"，
     * 本方法在覆盖写入时会把多余的块归还。</p>
     *
     * @return 实际写入的字节数
     */
    public int writeFile(int parentDirBlock, String name, String extension, int attributes, byte[] data) {
        checkName(name, extension);
        if (data == null || data.length > 65535) throw new IllegalArgumentException("文件长度不合法");
        return transaction(() -> writeFileInternal(parentDirBlock, name, extension, attributes, data));
    }
    private int writeFileInternal(int parentDirBlock, String name, String extension, int attributes, byte[] data) {
        String fullName = name + (extension.isEmpty() ? "" : "." + extension);
        FileEntry found = findInDirectory(parentDirBlock, fullName);
        if (found == null) {
            int first = allocateBlock();
            addEntry(parentDirBlock, name, extension, attributes, first, 0);
            found = findInDirectory(parentDirBlock, fullName);
        } else if (found.isDirectory()) {
            throw new IllegalStateException("不能把目录当文件写入：" + fullName);
        } else if (found.isReadonly()) {
            throw new IllegalStateException("文件 " + found.displayName() + " 是只读文件，禁止写入");
        }

        List<Integer> chain = new ArrayList<>(chain(found.startBlock()));
        // startBlock 可能是 0（表示尚未分配）
        if (found.startBlock() == DiskLayout.FAT_FREE) {
            int first = allocateBlock();
            updateEntry(parentDirBlock, fullName, found.attributes(), first, 0);
            chain = new ArrayList<>(List.of(first));
        }

        int need = (data.length + VirtualDisk.BLOCK_SIZE - 1) / VirtualDisk.BLOCK_SIZE;
        while (chain.size() < need) {
            int nb = allocateBlock();
            link(chain.get(chain.size() - 1), nb);
            chain.add(nb);
        }
        while (chain.size() > Math.max(1, need)) {
            int last = chain.remove(chain.size() - 1);
            link(chain.get(chain.size() - 1), DiskLayout.FAT_EOC);
            freeBlock(last);
        }
        if (chain.size() == 1) {
            link(chain.get(0), DiskLayout.FAT_EOC);
        }

        int done = 0;
        for (int block : chain) {
            byte[] blk = new byte[VirtualDisk.BLOCK_SIZE];
            int n = Math.min(VirtualDisk.BLOCK_SIZE, data.length - done);
            if (n > 0) {
                System.arraycopy(data, done, blk, 0, n);
            }
            disk.writeBlock(block, blk);
            done += Math.max(0, n);
        }
        updateEntry(parentDirBlock, fullName, found.attributes(), found.startBlock(), data.length);
        bump();
        return data.length;
    }

    /** 建立一个新的空文件，并占用至少 1 个盘块（指导书：每个文件至少占据一个磁盘块）。 */
    public void createFile(String path, int attributes, byte[] content) {
        path = normalize(path);
        String base = baseName(path);
        String ext = extensionOf(path);
        checkName(base, ext);
        String dirPath = path.substring(0, path.lastIndexOf('/'));
        int parentBlock = dirPath.isEmpty() ? DiskLayout.ROOT_BLOCK : requireDir(dirPath);
        if (findInDirectory(parentBlock, base + (ext.isEmpty() ? "" : "." + ext)) != null) {
            throw new IllegalStateException("同名文件已存在：" + path);
        }
        byte[] payload = (content == null || content.length == 0) ? new byte[0] : content;
        writeFile(parentBlock, base, ext, attributes, payload);
    }

    /**
     * 校验文件名/目录名是否符合指导书规定的字段长度。
     * <p>目录项只有 8 字节，其中文件名 3 字节、扩展名 1 字节——名字超长无法表示，
     * 因此这里直接报错，而不是悄悄截断（截断会导致"刚创建的文件找不到"）。</p>
     */
    public static void checkName(String base, String ext) {
        if (base == null || base.isEmpty()) {
            throw new IllegalArgumentException("文件名/目录名不能为空");
        }
        if (base.length() > DiskLayout.NAME_LEN) {
            throw new IllegalArgumentException("文件名或目录名最多 " + DiskLayout.NAME_LEN
                    + " 个字符（目录项只给它 " + DiskLayout.NAME_LEN + " 字节），" + base
                    + " 有 " + base.length() + " 个字符");
        }
        if (ext != null && ext.length() > DiskLayout.EXT_LEN) {
            throw new IllegalArgumentException("扩展名最多 " + DiskLayout.EXT_LEN
                    + " 个字符（如 e），" + ext + " 有 " + ext.length() + " 个字符");
        }
        for (char c : base.toCharArray()) {
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9')) {
                throw new IllegalArgumentException("文件名/目录名只允许字母和数字，出现非法字符：" + c);
            }
        }
        if (ext != null && !ext.isEmpty() && !ext.matches("[A-Za-z0-9]")) throw new IllegalArgumentException("扩展名必须是一个ASCII字母或数字");
    }

    /** 建立子目录：占一个盘块存放空的目录项表。 */
    public void createDirectory(String path) {
        String normalized = normalize(path);
        transaction(() -> { createDirectoryInternal(normalized); return null; });
    }
    private void createDirectoryInternal(String path) {
        if (!extensionOf(path).isEmpty()) throw new IllegalArgumentException("目录没有扩展名");
        String base = baseName(path);
        checkName(base, "");
        String dirPath = path.substring(0, path.lastIndexOf('/'));
        int parentBlock = dirPath.isEmpty() ? DiskLayout.ROOT_BLOCK : requireDir(dirPath);
        if (findInDirectory(parentBlock, base) != null) {
            throw new IllegalStateException("同名目录或文件已存在：" + path);
        }
        int block = allocateBlock();
        disk.writeBlock(block, new byte[VirtualDisk.BLOCK_SIZE]);
        addEntry(parentBlock, base, "", DiskLayout.ATTR_DIRECTORY, block, 0);
    }

    /** 删除文件：归还其占用的全部盘块，并清空目录项。 */
    public void deleteFile(String path) {
        transaction(() -> { deleteFileInternal(path); return null; });
    }
    private void deleteFileInternal(String path) {
        FileEntry e = require(path);
        if (e.isDirectory()) {
            throw new IllegalStateException(path + " 是目录，请使用 rmdir / deldir");
        }
        if (e.isReadonly()) {
            throw new IllegalStateException(path + " 是只读文件，禁止删除");
        }
        int parent = parentDirBlock(path);
        for (int block : chain(e.startBlock())) {
            freeBlock(block);
        }
        clearEntry(parent, e.displayName());
    }

    /** 删除空目录；目录非空时报错（指导书明确要求）。 */
    public void removeEmptyDirectory(String path) {
        transaction(() -> { removeEmptyDirectoryInternal(path); return null; });
    }
    private void removeEmptyDirectoryInternal(String path) {
        FileEntry e = require(path);
        if (!e.isDirectory()) {
            throw new IllegalStateException(path + " 不是目录");
        }
        if (e.isReadonly()) throw new IllegalStateException("只读目录禁止删除：" + path);
        if (e.startBlock() == DiskLayout.ROOT_BLOCK) {
            throw new IllegalStateException("根目录不能删除");
        }
        if (!list(e.startBlock()).isEmpty()) {
            throw new IllegalStateException("目录非空，不能删除：" + path);
        }
        int parent = parentDirBlock(path);
        for (int block : chain(e.startBlock())) {
            freeBlock(block);
        }
        clearEntry(parent, e.name());
    }

    /** 删除目录（空或非空都可以，对应可选命令 deldir）。 */
    public void removeDirectoryRecursively(String path) {
        transaction(() -> { removeDirectoryRecursivelyInternal(path); return null; });
    }
    private void removeDirectoryRecursivelyInternal(String path) {
        FileEntry e = require(path);
        if (!e.isDirectory()) {
            throw new IllegalStateException(path + " 不是目录");
        }
        if (e.isReadonly()) throw new IllegalStateException("只读目录禁止删除：" + path);
        for (FileEntry child : list(e.startBlock())) {
            String childPath = normalize(path) + "/" + child.name() + (child.extension().isEmpty() ? "" : "." + child.extension());
            if (child.isDirectory()) {
                removeDirectoryRecursively(childPath);
            } else {
                deleteFile(childPath);
            }
        }
        removeEmptyDirectory(path);
    }

    /** 清空某个目录项槽位（首字节写 0）。 */
    private void clearEntry(int dirStartBlock, String name) {
        for (int block : chain(dirStartBlock)) {
            byte[] data = disk.readBlock(block);
            for (int off = 0; off < VirtualDisk.BLOCK_SIZE; off += DiskLayout.ENTRY_SIZE) {
                if (FileEntry.isEmptySlot(data, off)) {
                    continue;
                }
                FileEntry e = FileEntry.unpack(data, off, off);
                if (e.displayName().equalsIgnoreCase(name.trim())) {
                    Arrays.fill(data, off, off + DiskLayout.ENTRY_SIZE, (byte) 0);
                    disk.writeBlock(block, data);
                    bump();
                    return;
                }
            }
        }
    }

    /** 修改文件属性（change 命令）。 */
    public void changeAttributes(String path, int attributes) {
        transaction(() -> {
            FileEntry e = require(path);
            int parent = parentDirBlock(path);
            int preserved = (attributes & ~DiskLayout.ATTR_DIRECTORY) | (e.attributes() & DiskLayout.ATTR_DIRECTORY);
            updateEntry(parent, e.displayName(), preserved, e.startBlock(), e.length());
            return null;
        });
    }

    /** 要求路径存在，否则抛错。 */
    public FileEntry require(String path) {
        FileEntry e = resolve(path);
        if (e == null) {
            throw new IllegalStateException("文件或目录不存在：" + path);
        }
        return e;
    }

    /** 要求路径是存在的目录，返回其起始盘块。 */
    public int requireDir(String path) {
        FileEntry e = require(path);
        if (!e.isDirectory()) {
            throw new IllegalStateException("不是目录：" + path);
        }
        return e.startBlock();
    }

    /** 拷贝文件：读源文件字节流，写到目标路径（隐含 create + write）。 */
    public void copyFile(String srcPath, String dstPath) {
        FileEntry src = require(srcPath);
        if (src.isDirectory()) {
            throw new IllegalStateException("暂不支持目录拷贝：" + srcPath);
        }
        byte[] data = readAll(src);
        createFile(dstPath, src.attributes() & ~DiskLayout.ATTR_SYSTEM, data);
    }

    // ==================================================================
    // 目录树（界面显示用）
    // ==================================================================

    /** 目录树节点，供界面"磁盘目录结构"一栏渲染。 */
    public record DirNode(String name, boolean directory, int startBlock, int length, String attributes,
                          List<DirNode> children) {
    }

    public DirNode tree(String path) {
        FileEntry e = resolve(path);
        if (e == null) {
            return null;
        }
        return buildNode(e.displayName(), e);
    }

    public DirNode rootTree() {
        List<DirNode> children = new ArrayList<>();
        for (FileEntry c : listRoot()) {
            children.add(buildNode(c.displayName(), c));
        }
        return new DirNode("/", true, DiskLayout.ROOT_BLOCK, 0, "根目录", List.copyOf(children));
    }

    private DirNode buildNode(String name, FileEntry e) {
        List<DirNode> children = new ArrayList<>();
        if (e.isDirectory()) {
            for (FileEntry c : list(e.startBlock())) {
                children.add(buildNode(c.displayName(), c));
            }
        }
        return new DirNode(name, e.isDirectory(), e.startBlock(), e.length(), e.attrText(), List.copyOf(children));
    }

    /** FAT 中每个盘块的占用情况快照（界面"磁盘使用情况"用）。 */
    public int[] fatSnapshot() {
        int[] out = new int[fat.length];
        for (int i = 0; i < fat.length; i++) {
            out[i] = fat[i] & 0xFF;
        }
        return out;
    }
}
