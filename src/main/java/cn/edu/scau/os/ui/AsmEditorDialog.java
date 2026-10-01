package cn.edu.scau.os.ui;

import cn.edu.scau.os.disk.DiskLayout;
import cn.edu.scau.os.disk.FileEntry;
import cn.edu.scau.os.instruction.Assembler;
import cn.edu.scau.os.kernel.Kernel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 汇编编辑器对话框：左侧写源码，右侧实时看编译结果，点一下就存成磁盘上的可执行文件。
 *
 * <p>这是指导书所说"汇编编译的模拟过程"的可视化入口，把原先只能用命令行
 * {@code create /aa/bb.e x=1|x++|end} 完成的事变成可视化编辑：</p>
 * <pre>
 *   源码编辑（每行一条指令，支持 // 与 # 注释）
 *        ↓ Assembler.assemble（编译，语法错误按行号报出）
 *   机器码（1 字节/条，!A? 设备指令 2 字节）+ 反汇编清单 + 十六进制转储
 *        ↓ Kernel.assembleAndSave（校验文件名 → 父目录必须存在 → 写入磁盘块）
 *   磁盘上的 .e 可执行文件（可用 create 命令创建进程运行）
 * </pre>
 *
 * <p>设计说明：本对话框是非模态的，打开时模拟仍在推进，方便"改一行 → 存盘 → 立刻 create"。
 * 保存动作不持有内核锁，依赖 {@link Kernel#assembleAndSave} 内部的加锁语义，
 * 因此与脉冲线程（心跳）不会互相破坏。</p>
 */
public class AsmEditorDialog extends JDialog implements DocumentListener {

    /**
     * 等宽字体：Latin 要等宽（列对齐），中文要真的能画出来。
     *
     * <p>注意：{@code Cascadia Code} / {@code Consolas} 在本机并不含中文字形，
     * 直接拿来显示中文会得到 8px 宽的 .notdef 方框（看起来像乱码），
     * 所以这里按候选顺序做"成对宽度"校验：一个汉字宽度应约等于两个半角字符宽度。</p>
     */
    private static final Font MONO = pickMonoFont();

    private static Font pickMonoFont() {
        for (String family : new String[]{"Microsoft YaHei UI", "微软雅黑", "新宋体", "宋体",
                Font.MONOSPACED, Font.DIALOG}) {
            Font f = new Font(family, Font.PLAIN, 13);
            if (hasRealCjkGlyphs(f)) {
                return f;
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, 13);
    }

    private static boolean hasRealCjkGlyphs(Font f) {
        java.awt.image.BufferedImage probe = new java.awt.image.BufferedImage(64, 32,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = probe.createGraphics();
        g.setFont(f);
        java.awt.FontMetrics fm = g.getFontMetrics();
        int latin = fm.charWidth('W');
        int cjk = fm.charWidth('编');
        g.dispose();
        return latin > 0 && cjk >= latin * 15 / 10;
    }

    /** 默认源码：一段含设备申请的小程序，直接可用作模板。 */
    private static final String DEFAULT_SOURCE = """
            // 汇编源码：每行一条指令；// 或 # 之后是注释
            x=3          // 立即数 0~63
            x++
            !A2          // 申请 A 设备 2 个时间单位
            x--
            end
            """;

    /** 示例模板：点"示例"菜单可插入。 */
    private static final String[][] EXAMPLES = {
            {"基础运算（x=3 → x++ → x-- → end）", """
                    x=3
                    x++
                    x--
                    end
                    """},
            {"循环计数（片内自减，演示时间片轮转）", """
                    x=12
                    x--
                    x--
                    x--
                    x--
                    end
                    """},
            {"申请 A 设备（演示阻塞、唤醒、I/O 中断）", """
                    x=1
                    !A8      // 申请 A 设备 8 个时间单位：进程转入阻塞
                    x++
                    end
                    """},
            {"三设备轮换（A → B → C，演示设备等待队列）", """
                    x=9
                    !A1
                    !B1
                    !C1
                    end
                    """}
    };

    /**
     * 源码存档文件名（放在 {@code runtime/} 下，与模拟磁盘同目录）。
     * 命名为 8.3 风格而不是 {@code .java/.txt}，是为了和项目里模拟磁盘的
     * "3 字符主名 + 1 字符扩展名"约束保持一致，避免和真实源码混淆。
     */
    private static final String SOURCE_FILE_NAME = "PmPt.asm";
    private static final String LEGACY_SOURCE_FILE_NAME = "PmPt.txt";

    private final Kernel kernel;

    private final JTextArea sourceArea = new JTextArea();
    private final JTextField pathField = new JTextField("/bin/new.e", 18);
    private final JComboBox<String> dirBox = new JComboBox<>(new String[]{"/", "/bin", "/doc", "/aa", "/dd", "/xx"});

    private final JTextArea listingArea = monoArea(10);
    private final JTextArea errorArea = monoArea(4);
    private final JTextArea disasmArea = monoArea(8);
    private final JTextArea hexArea = monoArea(3);
    private final JLabel status = new JLabel(" ");
    private final JLabel sourceFileLabel = new JLabel(" ");

    private Assembler.Result lastResult;
    private boolean disposed;
    private boolean sourceDirty;

    public AsmEditorDialog(Frame owner, Kernel kernel) {
        super(owner, "汇编编辑器 —— 源码 → 机器码 → 磁盘 .e 文件", false);
        this.kernel = kernel;

        sourceArea.setFont(MONO);
        sourceArea.setTabSize(4);
        sourceArea.setText(loadInitialSource());
        sourceArea.setCaretPosition(0);
        errorArea.setForeground(new Color(0xB0, 0x1B, 0x1B));

        setLayout(new BorderLayout(8, 8));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        setSize(980, 660);
        setLocationRelativeTo(owner);
        sourceArea.getDocument().addDocumentListener(this);
        sourceDirty = false;
        updateSourceFileLabel();
        doCompile();
    }

    // ==================================================================
    // 布局
    // ==================================================================

    private JPanel buildTop() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        panel.setBorder(BorderFactory.createTitledBorder(
                "保存位置：3 个字符以内的主名 + 1 个字符扩展名（例如 /bin/new.e）"));

        panel.add(new JLabel("路径："));
        pathField.setFont(MONO);
        panel.add(pathField);

        JButton browse = new JButton("已存在目录…");
        browse.addActionListener(e -> chooseDirectory());
        panel.add(browse);

        panel.add(Box.createHorizontalStrut(8));
        panel.add(new JLabel("常用目录："));
        dirBox.addActionListener(e -> {
            String dir = (String) dirBox.getSelectedItem();
            if (dir != null) {
                pathField.setText(dir.equals("/") ? "/new.e" : dir + "/new.e");
                pathField.requestFocusInWindow();
            }
        });
        panel.add(dirBox);

        JButton example = new JButton("示例 ▾");
        example.addActionListener(e -> showExamples(example));
        panel.add(example);

        JLabel hint = new JLabel("（源码自动存档，重启后自动载回；保存后可用 create 命令创建进程运行）");
        hint.setForeground(Color.GRAY);
        panel.add(hint);
        panel.add(sourceFileLabel);
        return panel;
    }

    private JPanel buildCenter() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                scroll(sourceArea, "汇编源码（每行一条指令）"), rightPanel());
        split.setResizeWeight(0.45);
        split.setDividerLocation(420);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel rightPanel() {
        JPanel panel = new JPanel(new GridLayout(4, 1, 4, 4));
        panel.add(scroll(listingArea, "编译清单（偏移 : 机器码 : 助记符）"));
        panel.add(scroll(disasmArea, "反汇编回读（模拟 CPU 取指视图）"));
        panel.add(scroll(hexArea, "机器码十六进制转储"));
        panel.add(scroll(errorArea, "编译错误（按源码行号报告；无错误时为空）"));
        return panel;
    }

    private JPanel buildBottom() {
        JPanel panel = new JPanel(new BorderLayout(6, 4));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(status);
        panel.add(left, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton compile = new JButton("编译（Ctrl+B）");
        compile.addActionListener(e -> {
            doCompile();
            appendLog(lastResult != null && lastResult.ok()
                    ? "汇编编辑器：编译通过"
                    : "汇编编辑器：编译失败，共 " + (lastResult == null ? 0 : lastResult.errors().size()) + " 条错误");
        });
        JButton save = new JButton("编译并保存为 .e（Ctrl+S）");
        save.addActionListener(e -> doSave());
        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());
        buttons.add(compile);
        buttons.add(save);
        buttons.add(close);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    /**
     * 列出磁盘上真实存在的目录，供用户挑选保存位置（避免手打路径出错）。
     * 根目录固定在第 4 块，其余目录从根目录递归扫描。
     */
    private void chooseDirectory() {
        java.util.List<String> dirs = new java.util.ArrayList<>();
        dirs.add("/");
        collectDirs(DiskLayout.ROOT_BLOCK, "", dirs);
        Object choice = JOptionPane.showInputDialog(this, "选择保存目录：", "已存在目录",
                JOptionPane.PLAIN_MESSAGE, null, dirs.toArray(new String[0]), dirs.get(0));
        if (choice != null) {
            String dir = (String) choice;
            pathField.setText(dir.equals("/") ? "/new.e" : dir + "/new.e");
            pathField.requestFocusInWindow();
        }
    }

    private void collectDirs(int block, String prefix, java.util.List<String> out) {
        for (FileEntry e : kernel.fs().list(block)) {
            if (e.isDirectory()) {
                String path = prefix + "/" + e.name();
                out.add(path);
                collectDirs(e.startBlock(), path, out);
            }
        }
    }

    private void showExamples(JButton anchor) {
        JPopupMenu menu = new JPopupMenu();
        for (String[] ex : EXAMPLES) {
            JMenuItem item = new JMenuItem(ex[0]);
            item.addActionListener(e -> {
                sourceArea.setText(ex[1]);
                sourceArea.setCaretPosition(0);
                doCompile();
            });
            menu.add(item);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    // ==================================================================
    // 编译 / 保存
    // ==================================================================

    /** 编译当前源码并刷新右侧 4 个显示区；返回编译结果（失败时 errors 非空）。 */
    private Assembler.Result doCompile() {
        if (disposed) {
            return null;
        }
        Assembler.Result r = Assembler.assemble(sourceArea.getText());
        lastResult = r;

        listingArea.setText(String.join("\n", r.listing()));
        listingArea.setCaretPosition(0);

        byte[] code = r.code();
        disasmArea.setText(code.length == 0 ? "（无机器码）" : Assembler.disassemble(code));
        disasmArea.setCaretPosition(0);

        hexArea.setText(code.length == 0 ? "（无机器码）" : hexDump(code));
        hexArea.setCaretPosition(0);

        errorArea.setText(r.errors().isEmpty() ? "" : String.join("\n", r.errors()));
        errorArea.setCaretPosition(0);
        updateSourceFileLabel();

        if (r.ok()) {
            status.setForeground(new Color(0x1B, 0x6B, 0x2A));
            status.setText("编译通过：共 " + code.length + " 字节机器码");
            setTitle("汇编编辑器 —— 源码 → 机器码 → 磁盘 .e 文件");
        } else {
            status.setForeground(new Color(0xB0, 0x1B, 0x1B));
            status.setText("编译失败：共 " + r.errors().size() + " 条错误");
            setTitle("汇编编辑器（编译失败）—— 源码 → 机器码 → 磁盘 .e 文件");
        }
        return r;
    }

    /** 编译并落盘：先校验路径与文件名，再走内核的 assembleAndSave。 */
    private void doSave() {
        String path = pathField.getText().trim();
        if (path.isEmpty()) {
            warn("请先填写保存路径，例如 /bin/new.e");
            return;
        }
        if (!path.startsWith("/")) {
            warn("路径必须以 / 开头（根目录），例如 /bin/new.e");
            return;
        }
        Assembler.Result r = doCompile();
        if (r == null || !r.ok()) {
            warn("源码有 " + (r == null ? 0 : r.errors().size()) + " 条编译错误，未保存。\n"
                    + "请修正右侧“编译错误”区列出的问题后重试。");
            return;
        }
        try {
            kernel.assembleAndSave(path, sourceArea.getText());
        } catch (RuntimeException ex) {
            warn("保存失败：" + ex.getMessage());
            return;
        }
        persistSource();

        String msg = "已保存 " + path + "\n"
                + "机器码 " + r.code().length + " 字节（磁盘块 64 字节，占用 "
                + (int) Math.ceil(r.code().length / 64.0) + " 块）\n"
                + sourceFileMessage() + "\n\n"
                + "现在创建进程运行它吗？";
        int choice = JOptionPane.showConfirmDialog(this, msg, "编译并保存",
                JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            int pid = kernel.create(path);
            if (pid >= 0) {
                appendLog("汇编编辑器：已创建进程 P" + pid + " 运行 " + path);
            } else {
                warn("进程创建失败（PCB 已满或文件不可读），请查看主窗口日志。");
            }
        }
    }

    // ==================================================================
    // 源码存档：让编辑器里的源码在重启后还在
    // ==================================================================

    /**
     * 源码存档文件（默认 {@code runtime/PmPt.asm}）。找不到可用位置时返回 null，
     * 此时编辑器仍可用，只是源码不跨次启动保留。
     */
    private static Path resolveSourceFile() {
        Path runtimeDir = Paths.get("runtime");
        Path preferred = runtimeDir.resolve(SOURCE_FILE_NAME);
        if (Files.isRegularFile(preferred)) {
            return preferred;
        }
        Path legacy = runtimeDir.resolve(LEGACY_SOURCE_FILE_NAME);
        if (Files.isRegularFile(legacy)) {
            return legacy;
        }
        if (Files.isDirectory(runtimeDir)) {
            return preferred;
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), SOURCE_FILE_NAME);
    }

    /** 打开对话框时的初始源码：有存档就用存档，否则用内置模板。 */
    private static String loadInitialSource() {
        Path file = resolveSourceFile();
        if (file != null && Files.isRegularFile(file)) {
            String text = readSourceFile(file);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return DEFAULT_SOURCE;
    }

    static String readSourceFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把当前源码写回存档文件；返回写到哪儿（null = 写失败）。 */
    private Path persistSource() {
        Path file = resolveSourceFile();
        if (file == null || !writeSourceFile(file, sourceArea.getText())) {
            return null;
        }
        sourceDirty = false;
        return file;
    }

    static boolean writeSourceFile(Path file, String text) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 顶部状态标签：告知源码存档在哪儿。 */
    private void updateSourceFileLabel() {
        Path file = resolveSourceFile();
        if (file == null) {
            sourceFileLabel.setText("（无源码存档，源码不跨次启动保留）");
            sourceFileLabel.setForeground(new Color(0xB0, 0x1B, 0x1B));
            return;
        }
        sourceFileLabel.setText("源码存档 → " + file.toAbsolutePath());
        sourceFileLabel.setForeground(new Color(0x1B, 0x6B, 0x2A));
    }

    /** "编译并保存"确认框里显示的一行存档位置说明。 */
    private String sourceFileMessage() {
        Path file = resolveSourceFile();
        if (file == null) {
            return "（源码未能存档，本次编辑不会保留）";
        }
        return "源码已存档 → " + file.toAbsolutePath();
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, "汇编编辑器", JOptionPane.WARNING_MESSAGE);
    }

    private void appendLog(String text) {
        Frame owner = (getOwner() instanceof Frame f) ? f : null;
        if (owner instanceof MainFrame mf) {
            mf.appendLog(text);
        }
    }

    private static String hexDump(byte[] code) {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < code.length; row += 16) {
            sb.append(String.format("%04X: ", row));
            for (int i = row; i < Math.min(row + 16, code.length); i++) {
                sb.append(String.format("%02X ", code[i] & 0xFF));
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static JScrollPane scroll(JTextArea area, String title) {
        JScrollPane sp = new JScrollPane(area);
        sp.setBorder(BorderFactory.createTitledBorder(title));
        return sp;
    }

    private static JTextArea monoArea(int rows) {
        JTextArea area = new JTextArea(rows, 40);
        area.setFont(MONO);
        area.setEditable(false);
        area.setLineWrap(false);
        return area;
    }

    // ---- 源码变动即重算预览 ----

    @Override
    public void insertUpdate(DocumentEvent e) {
        sourceDirty = true;
        doCompile();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        sourceDirty = true;
        doCompile();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        // 纯文本组件不会触发该事件
    }

    @Override
    public void dispose() {
        disposed = true;
        sourceArea.getDocument().removeDocumentListener(this);
        // 关窗口时把没保存过的编辑也存档，下次打开还在
        if (sourceDirty) {
            persistSource();
        }
        super.dispose();
    }

    /** 便于命令行/自检调用的静态入口：打开编辑器。 */
    public static void open(Frame owner, Kernel kernel) {
        SwingUtilities.invokeLater(() -> new AsmEditorDialog(owner, kernel).setVisible(true));
    }
}
