package cn.edu.scau.os.disk;

import java.nio.charset.StandardCharsets;

/**
 * 目录项：固定 8 字节，字段布局完全按指导书规定。
 *
 * <pre>
 * 偏移  长度  字段        说明
 * 0     3     目录名/文件名  合法字符：字母、数字（指导书只允许这两类），不足补空格
 * 3     1     扩展名       可执行文件为 'e'；目录没有扩展名，填空格
 * 4     1     属性         位定义见 DiskLayout.ATTR_*，ATTR_DIRECTORY 置 1 表示目录
 * 5     1     起始盘号     指向 FAT 的入口
 * 6     2     长度         文件长度（本原型按"字节数"记录，上限 65535）
 * </pre>
 *
 * <p>首字节为 0 表示该目录项为空。</p>
 */
public record FileEntry(int nameOffsetInBlock,
                        String name,
                        String extension,
                        int attributes,
                        int startBlock,
                        int length,
                        byte[] raw) {

    public boolean isDirectory() {
        return (attributes & DiskLayout.ATTR_DIRECTORY) != 0;
    }

    public boolean isReadonly() {
        return (attributes & DiskLayout.ATTR_READONLY) != 0;
    }

    public boolean isSystem() {
        return (attributes & DiskLayout.ATTR_SYSTEM) != 0;
    }

    public boolean isExecutable() {
        return !isDirectory() && DiskLayout.EXT_EXECUTABLE.equalsIgnoreCase(extension.trim());
    }

    /** 可执行文件的实际字节数受盘块数限制，链长度上限由 FAT 保证。 */
    public String displayName() {
        String n = name.trim();
        String e = extension.trim();
        if (e.isEmpty()) {
            return n;
        }
        return n + "." + e;
    }

    public String attrText() {
        StringBuilder sb = new StringBuilder();
        if (isDirectory()) {
            sb.append("目录");
        }
        if (isReadonly()) {
            sb.append(sb.isEmpty() ? "" : "|").append("只读");
        }
        if (isSystem()) {
            sb.append(sb.isEmpty() ? "" : "|").append("系统");
        }
        if (!isDirectory() && (attributes & DiskLayout.ATTR_NORMAL) != 0) {
            sb.append(sb.isEmpty() ? "" : "|").append("普通");
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
    }

    /** 打包成 8 字节。 */
    public static byte[] pack(String name, String extension, int attributes, int startBlock, int length) {
        byte[] b = new byte[DiskLayout.ENTRY_SIZE];
        putPadded(b, 0, DiskLayout.NAME_LEN, name);
        putPadded(b, DiskLayout.NAME_LEN, DiskLayout.EXT_LEN, extension);
        b[4] = (byte) (attributes & 0xFF);
        b[5] = (byte) (startBlock & 0xFF);
        b[6] = (byte) (length & 0xFF);
        b[7] = (byte) ((length >>> 8) & 0xFF);
        return b;
    }

    /** 解析 8 字节。首字节为 0 或长度为 0 的英文名视为空项。 */
    public static FileEntry unpack(byte[] b, int offset, int nameOffsetInBlock) {
        byte[] raw = new byte[DiskLayout.ENTRY_SIZE];
        System.arraycopy(b, offset, raw, 0, DiskLayout.ENTRY_SIZE);
        String name = new String(raw, 0, DiskLayout.NAME_LEN, StandardCharsets.US_ASCII).trim();
        String ext = new String(raw, DiskLayout.NAME_LEN, DiskLayout.EXT_LEN, StandardCharsets.US_ASCII).trim();
        int attr = raw[4] & 0xFF;
        int start = raw[5] & 0xFF;
        int len = (raw[6] & 0xFF) | ((raw[7] & 0xFF) << 8);
        return new FileEntry(nameOffsetInBlock, name, ext, attr, start, len, raw);
    }

    /** 首字节为 0（或全为空格）即视为空目录项。 */
    public static boolean isEmptySlot(byte[] block, int offset) {
        if ((block[offset] & 0xFF) == 0) {
            return true;
        }
        return block[offset] == 0x20 && block[offset + 1] == 0x20 && block[offset + 2] == 0x20;
    }

    private static void putPadded(byte[] dest, int off, int len, String s) {
        for (int i = 0; i < len; i++) {
            dest[off + i] = (byte) ' ';
        }
        if (s == null) {
            return;
        }
        byte[] src = s.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < Math.min(len, src.length); i++) {
            dest[off + i] = src[i];
        }
    }
}
