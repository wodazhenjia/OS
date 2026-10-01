package cn.edu.scau.os.ui;

import cn.edu.scau.os.disk.DiskLayout;
import cn.edu.scau.os.kernel.Kernel;
import cn.edu.scau.os.instruction.Assembler;
import cn.edu.scau.os.storage.MemoryManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.List;
import java.util.function.Function;

/**
 * 主窗口：严格按指导书"图 1 模拟操作系统的屏幕显示布局和内容"组织面板。
 *
 * <p>图 1 要求的显示区块与实现对应关系：</p>
 * <pre>
 *   系统时钟                      → 状态盒 "系统时钟"
 *   CPU（正在运行进程 ID / 时间片）→ 状态盒 "正在运行进程 ID"、"时间片"
 *   用户命令接口                  → 顶部命令输入框 + 右侧命令日志
 *   就绪队列进程 ID                → 文本盒 "就绪队列进程 ID"
 *   阻塞队列进程 ID、等待时间       → 文本盒 "阻塞队列进程 ID，等待时间"
 *   执行进程中间结果               → 文本盒 "执行进程中间结果"
 *   磁盘目录结构                  → JTree
 *   正在执行的指令                 → 文本盒 "正在执行的指令"
 *   进程执行完，显示结果            → 文本盒 "进程执行完，显示结果"
 *   主存用户区使用情况             → 自绘彩色内存条
 *   磁盘使用情况                  → 自绘 16×16 盘块位图
 *   设备使用情况：是否分配，占用进程，等待进程 → 表格化文本盒
 * </pre>
 */
public class MainFrame extends JFrame {

    private final Kernel kernel;
    private final Timer refreshTimer;

    // ---- 状态显示控件 ----
    private final JLabel clockValue = bigLabel("0");
    private final JLabel runningValue = bigLabel("—");
    private final JLabel sliceValue = bigLabel("6");
    private final JTextArea readyArea = monoArea(3);
    private final JTextArea blockedArea = monoArea(3);
    private final JTextArea resultArea = monoArea(4);
    private final JTextArea instrArea = monoArea(4);
    private final JTextArea memText = monoArea(3);
    private final JTextArea diskText = monoArea(3);
    private final JTextArea deviceArea = monoArea(7);
    private final JTextArea pcbArea = monoArea(12);
    private final JTextArea cmdLog = monoArea(10);
    private final JTextField cmdField = new JTextField();

    // ---- 自绘面板 ----
    private final DrawPanel memPanel = new DrawPanel();
    private final DrawPanel diskPanel = new DrawPanel();

    private final DirectoryTree dirTree = new DirectoryTree();
    private final JCheckBox showFrozen = new JCheckBox("冻结视图（便于观察）");

    private Kernel.Snapshot last;

    public MainFrame(Kernel kernel) {
        super("SimOS —— 模拟操作系统（题目六原型）");
        this.kernel = kernel;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        // 按屏幕自适应：保证图 1 的四列布局在小屏上也能完整显示
        java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        int w = Math.max(1100, Math.min(1800, (int) (screen.width * 0.97)));
        int h = Math.max(700, Math.min(1040, (int) (screen.height * 0.95)));
        setSize(w, h);
        setLocation(screen.x + (screen.width - w) / 2, screen.y + Math.max(0, (screen.height - h) / 2));
        setLayout(new BorderLayout(6, 6));
        setJMenuBar(buildMenuBar());

        add(buildCenter(), BorderLayout.CENTER);
        add(buildSouth(), BorderLayout.SOUTH);

        // 界面刷新：每 200ms 取一次快照重画
        refreshTimer = new Timer(200, e -> refresh());
        refreshTimer.start();

        appendLog(Kernel.helpText());
        appendLog("提示：修改 disk 文件前请先暂停；点击“暂停”可冻结模拟以便观察。");
    }

    // ==================================================================
    // 布局
    // ==================================================================

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu sim = new JMenu("模拟控制");
        JMenuItem pause = new JMenuItem("暂停 / 继续");
        pause.addActionListener(e -> {
            kernel.cpu().setPaused(!kernel.cpu().isPaused());
            appendLog(kernel.cpu().isPaused() ? "模拟已暂停" : "模拟已继续");
        });
        JMenuItem step = new JMenuItem("单步执行一个时间单位");
        step.addActionListener(e -> {
            boolean was = kernel.cpu().isPaused();
            kernel.cpu().setPaused(true);
            synchronized (kernel.lock()) {
                kernel.cpu().cycle();
            }
            kernel.cpu().setPaused(was);
            refresh();
        });
        JMenuItem editor = new JMenuItem("汇编编辑器（写源码 → 编译 → 保存为 .e）");
        editor.addActionListener(e -> {
            appendLog("打开汇编编辑器：源码按行编写，// 或 # 之后为注释；"
                    + "保存后可用 create 命令创建进程运行。");
            AsmEditorDialog dialog = new AsmEditorDialog(this, kernel);
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent ev) {
                    refresh();
                    appendLog("汇编编辑器已关闭。");
                }
            });
            dialog.setVisible(true);
        });
        JMenuItem reset = new JMenuItem("重新初始化（格式化磁盘并重建演示数据）");
        reset.addActionListener(e -> {
            kernel.initialize((int) System.currentTimeMillis());
            appendLog("已重新初始化模拟系统");
            refresh();
        });
        JMenuItem quit = new JMenuItem("退出");
        quit.addActionListener(e -> {
            kernel.shutdown();
            dispose();
            System.exit(0);
        });
        sim.add(pause);
        sim.add(step);
        sim.addSeparator();
        sim.add(editor);
        sim.addSeparator();
        sim.add(reset);
        sim.addSeparator();
        sim.add(quit);

        JMenu fit = new JMenu("存储管理算法");
        for (MemoryManager.Fit f : MemoryManager.Fit.values()) {
            JMenuItem it = new JMenuItem(switch (f) {
                case FIRST -> "首次适应 (First Fit)";
                case NEXT -> "下次适应 (Next Fit)";
                case BEST -> "最佳适配 (Best Fit)";
            });
            it.addActionListener(e -> {
                kernel.memory().setFit(f);
                appendLog("存储管理算法切换为：" + it.getText());
            });
            fit.add(it);
        }

        JMenu help = new JMenu("帮助");
        JMenuItem cmd = new JMenuItem("命令用法");
        cmd.addActionListener(e -> JOptionPane.showMessageDialog(this, Kernel.helpText(), "命令用法", JOptionPane.INFORMATION_MESSAGE));
        JMenuItem about = new JMenuItem("关于 / 设计说明");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "SimOS —— 《操作系统分析与设计实习》题目六 模拟操作系统实现（原型）\n\n"
                        + "指令集（每条指令 1 字节）：\n"
                        + "  0x00-0x3F  x=?   赋值，立即数 0~63\n"
                        + "  0x40       x++   x 加 1\n"
                        + "  0x41       x--   x 减 1\n"
                        + "  0x42-0x44  !A?/!B?/!C?  申请设备，后跟 1 字节使用时间\n"
                        + "  0x45       end   结束\n\n"
                        + DiskLayout.describe() + "\n\n"
                        + "说明：指导书要求“FAT 占第 0、1 块”与“FAT 有 256 项（每项 1 字节）”"
                        + "无法同时成立；本项目保留 256 项 FAT，因此 FAT 占 0~3 块、根目录移到第 4 块。",
                "关于 SimOS", JOptionPane.INFORMATION_MESSAGE));
        help.add(cmd);
        help.add(about);

        bar.add(sim);
        bar.add(fit);
        bar.add(help);
        return bar;
    }

    private JPanel buildCenter() {
        JPanel root = new JPanel(new BorderLayout(6, 6));

        // 上部：图 1 的 11 个区块
        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = 1;
        c.gridy = 0;

        row(top, c, 0,
                tile("系统时钟", clockValue),
                tile("正在运行进程 ID / CPU", runningValue),
                tile("时间片", sliceValue),
                wideTile("用户命令接口（在下方输入框键入命令）", new JLabel("create / delete / type / copy / mkdir / rmdir …")));

        row(top, c, 1,
                textTile("就绪队列进程 ID", readyArea),
                textTile("阻塞队列进程 ID，等待时间", blockedArea),
                textTile("执行进程中间结果", resultArea),
                treeTile("磁盘目录结构", dirTree));

        memPanel.setBorder(titled("主存用户区使用情况（512 字节，彩色分块）"));
        diskPanel.setBorder(titled("磁盘使用情况（256 盘块，16×16）"));
        row(top, c, 2,
                textTile("正在执行的指令", instrArea),
                textTile("进程执行完，显示结果", resultAreaAlt()),
                memPanel,
                diskPanel);

        row(top, c, 3,
                textTile("设备使用情况：是否分配、占用进程、等待进程", deviceArea),
                textTile("内存分配表 / 磁盘摘要", memText),
                textTile("PCB 区（最多 10 个）", pcbArea),
                textTile("磁盘与 FAT 摘要", diskText));

        root.add(top, BorderLayout.CENTER);
        return root;
    }

    private final JTextArea resultAlt = monoArea(4);

    private JTextArea resultAreaAlt() {
        return resultAlt;
    }

    private JPanel buildSouth() {
        JPanel south = new JPanel(new BorderLayout(6, 6));

        JPanel cmdRow = new JPanel(new BorderLayout(6, 0));
        JButton run = new JButton("执行命令");
        JButton step = new JButton("单步");
        JButton pause = new JButton("暂停/继续");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

        run.addActionListener(e -> runCommand());
        cmdField.addActionListener(e -> runCommand());
        step.addActionListener(e -> {
            boolean was = kernel.cpu().isPaused();
            kernel.cpu().setPaused(true);
            synchronized (kernel.lock()) {
                kernel.cpu().cycle();
            }
            kernel.cpu().setPaused(was);
            refresh();
        });
        pause.addActionListener(e -> {
            kernel.cpu().setPaused(!kernel.cpu().isPaused());
            appendLog(kernel.cpu().isPaused() ? "模拟已暂停" : "模拟已继续");
        });

        buttons.add(run);
        buttons.add(step);
        buttons.add(pause);
        buttons.add(showFrozen);

        cmdRow.add(new JLabel("命令："), BorderLayout.WEST);
        cmdRow.add(cmdField, BorderLayout.CENTER);
        cmdRow.add(buttons, BorderLayout.EAST);

        JPanel speed = new JPanel(new BorderLayout(6, 0));
        JSlider slider = new JSlider(50, 1500, 500);
        slider.setInverted(true);
        slider.addChangeListener(e -> kernel.setPulseMillis(slider.getValue()));
        speed.add(new JLabel("时间单位长度（脉冲 ms，向左=更慢）"), BorderLayout.WEST);
        speed.add(slider, BorderLayout.CENTER);

        JPanel north = new JPanel(new GridLayout(2, 1, 0, 4));
        north.add(cmdRow);
        north.add(speed);

        JScrollPane logScroll = new JScrollPane(cmdLog);
        logScroll.setBorder(titled("运行日志（命令结果 + 内核事件）"));
        logScroll.setPreferredSize(new Dimension(1100, 190));

        south.add(north, BorderLayout.NORTH);
        south.add(logScroll, BorderLayout.CENTER);
        return south;
    }

    private static void row(JPanel p, GridBagConstraints c, int y, java.awt.Component... comps) {
        c.gridy = y;
        for (int i = 0; i < comps.length; i++) {
            c.gridx = i;
            c.weightx = (comps.length == 4) ? 1 : 1;
            p.add(comps[i], c);
        }
    }

    // ==================================================================
    // 组件工厂
    // ==================================================================

    private static JLabel bigLabel(String text) {
        JLabel l = new JLabel(text, JLabel.CENTER);
        l.setFont(new Font(Font.MONOSPACED, Font.BOLD, 17));
        l.setForeground(new Color(0x1B4F9C));
        return l;
    }

    static JTextArea monoArea(int rows) {
        JTextArea a = new JTextArea(rows, 14);
        a.setEditable(false);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        a.setBackground(new Color(0xFAFBFF));
        return a;
    }

    static TitledBorder titled(String title) {
        TitledBorder b = BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title);
        b.setTitleFont(new Font("Microsoft YaHei", Font.BOLD, 11));
        return b;
    }

    private static JPanel tile(String title, JLabel value) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(titled(title));
        p.add(value, BorderLayout.CENTER);
        return p;
    }

    private static JPanel tile(String title, java.awt.Component comp) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(titled(title));
        p.add(comp, BorderLayout.CENTER);
        return p;
    }

    private static JPanel wideTile(String title, java.awt.Component comp) {
        return tile(title, comp);
    }

    private static JPanel textTile(String title, JTextArea area) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(titled(title));
        p.add(new JScrollPane(area), BorderLayout.CENTER);
        return p;
    }

    private static JPanel treeTile(String title, JTree tree) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(titled(title));
        tree.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        tree.setRootVisible(true);
        p.add(new JScrollPane(tree), BorderLayout.CENTER);
        return p;
    }

    // ==================================================================
    // 刷新与命令
    // ==================================================================

    private void runCommand() {
        String line = cmdField.getText();
        if (line == null || line.isBlank()) {
            return;
        }
        cmdField.setText("");
        Kernel.CommandResult r = kernel.exec(line);
        appendLog("$ " + line);
        if (!r.message().isEmpty()) {
            appendLog(r.message());
        }
        if (!r.ok()) {
            appendLog("[失败] " + r.message());
        }
        refresh();
    }

    /** 供其他窗口（如汇编编辑器）向主窗口日志追加内容。 */
    public void appendLog(String text) {
        cmdLog.append(text.endsWith("\n") ? text : text + "\n");
        cmdLog.setCaretPosition(cmdLog.getDocument().getLength());
    }

    private void refresh() {
        if (showFrozen.isSelected() && last != null) {
            return;
        }
        Kernel.Snapshot s = kernel.snapshot();
        last = s;
        render(s);
    }

    private void render(Kernel.Snapshot s) {
        clockValue.setText(String.valueOf(s.systemClock()));
        Kernel.PcbView running = s.pcbs().stream().filter(p -> "运行".equals(p.state())).findFirst().orElse(null);
        runningValue.setText(running == null ? "—" : "P" + running.pid() + "  " + running.name());
        sliceValue.setText(running == null ? "—" : String.valueOf(running.timeSliceLeft()));

        readyArea.setText(formatIds(s.readyQueue()));
        blockedArea.setText(formatBlocked(s));
        resultArea.setText(running == null ? "—" : "P" + running.pid() + "  x = " + running.ax() + "  PC = " + running.pc());
        instrArea.setText(s.currentInstruction() + "\n事件：" + s.lastEvent());
        resultAlt.setText(String.join("\n", s.results()));
        deviceArea.setText(formatDevices(s));
        memText.setText(formatMemory(s));
        diskText.setText(formatDisk(s));
        pcbArea.setText(formatPcbs(s));

        dirTree.update(s.rootChildren());
        memPanel.setOwners(s.memoryOwners());
        diskPanel.setFat(s.fat());
        memPanel.repaint();
        diskPanel.repaint();

        // 内核日志增量追加
        String[] lines = s.logLines().toArray(new String[0]);
        if (lastLogSize > lines.length) {
            lastLogSize = 0;
        }
        for (int i = lastLogSize; i < lines.length; i++) {
            appendLog(lines[i]);
        }
        lastLogSize = lines.length;
    }

    private int lastLogSize;

    private static String formatIds(List<Integer> ids) {
        if (ids.isEmpty()) {
            return "(空)";
        }
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            sb.append("P").append(id).append(' ');
        }
        return sb.toString().trim();
    }

    private String formatBlocked(Kernel.Snapshot s) {
        if (s.blockedQueue().isEmpty()) {
            return "(空)";
        }
        StringBuilder sb = new StringBuilder();
        for (int pid : s.blockedQueue()) {
            Kernel.PcbView v = s.pcbs().get(pid);
            sb.append("P").append(pid).append("  ").append(v.blockReason())
                    .append(v.device() == null || v.device().equals("—") ? "" : "（" + v.device() + " 剩余 " + deviceRemaining(s, v.device()) + "）")
                    .append('\n');
        }
        return sb.toString();
    }

    private static int deviceRemaining(Kernel.Snapshot s, String deviceName) {
        for (Kernel.DevView d : s.deviceViews()) {
            if (d.name().equals(deviceName)) {
                return d.remaining();
            }
        }
        return 0;
    }

    private static String formatDevices(Kernel.Snapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-4s %-6s %-8s %-8s %s%n", "设备", "状态", "占用进程", "剩余", "说明"));
        for (Kernel.DevView d : s.deviceViews()) {
            sb.append(String.format("%-5s %-7s %-9s %-9s%n",
                    d.name(), d.owner() < 0 ? "空闲" : "占用",
                    d.owner() < 0 ? "—" : "P" + d.owner(),
                    d.owner() < 0 ? "—" : d.remaining()));
        }
        for (Kernel.DevWaitView w : s.deviceWaitViews()) {
            sb.append(String.format("等待队列 %s：%s（共 %d 个，忙 %d）%n",
                    w.type(), w.waiting().isEmpty() ? "无" : w.waiting(), w.total(), w.busy()));
        }
        return sb.toString();
    }

    private static String formatMemory(Kernel.Snapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户区 512 字节，空闲 ").append(s.memoryFree()).append(" 字节\n");
        for (MemoryManager.Partition p : s.memoryPartitions()) {
            sb.append("  ").append(p).append('\n');
        }
        return sb.toString();
    }

    private static String formatDisk(Kernel.Snapshot s) {
        return String.format("""
                        已用盘块：%d
                        空闲盘块：%d
                        数据区起始盘块：%d
                        根目录盘块：%d
                        FAT 盘块：%d~%d
                        """,
                s.usedBlocks(), s.freeBlocks(), DiskLayout.FIRST_DATA_BLOCK, DiskLayout.ROOT_BLOCK,
                DiskLayout.FAT_START_BLOCK, DiskLayout.FAT_START_BLOCK + DiskLayout.FAT_BLOCKS - 1);
    }

    private static String formatPcbs(Kernel.Snapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-4s %-12s %-6s %-10s %-4s %-4s %-5s %-10s%n",
                "PID", "名称", "状态", "阻塞原因", "x", "PC", "片余", "内存"));
        for (Kernel.PcbView p : s.pcbs()) {
            sb.append(String.format("%-4d %-12s %-7s %-11s %-5d %-5d %-6d %-11s%n",
                    p.pid(), p.name(), p.state(), p.blockReason(), p.ax(), p.pc(), p.timeSliceLeft(),
                    p.memSize() == 0 ? "—" : p.memBase() + "+" + p.memSize()));
        }
        return sb.toString();
    }

    // ==================================================================
    // 自绘面板
    // ==================================================================

    /** 通用自绘面板：内存条 / 磁盘位图。 */
    static class DrawPanel extends JPanel {
        private int[] owners = new int[0];
        private int[] fat = new int[0];

        DrawPanel() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(300, 180));
        }

        void setOwners(int[] o) {
            this.owners = o;
        }

        void setFat(int[] f) {
            this.fat = f;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (fat.length > 0) {
                paintDisk(g);
            } else if (owners.length > 0) {
                paintMemory(g);
            }
        }

        private void paintMemory(Graphics2D g) {
            int w = getWidth() - 20;
            int h = 52;
            int x0 = 10;
            int y0 = 30;
            int n = owners.length;
            for (int i = 0; i < n; i++) {
                int x = x0 + (int) Math.round((double) i * w / n);
                int x2 = x0 + (int) Math.round((double) (i + 1) * w / n);
                g.setColor(owners[i] < 0 ? new Color(0xE8E8E8) : colorFor(owners[i]));
                g.fillRect(x, y0, Math.max(1, x2 - x), h);
            }
            g.setColor(Color.DARK_GRAY);
            g.drawRect(x0, y0, w, h);
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            g.drawString("0", x0, y0 + h + 14);
            g.drawString("512 字节", x0 + w - 52, y0 + h + 14);

            // 图例
            int y = y0 + h + 32;
            g.drawString("空闲", x0, y + 10);
            g.setColor(new Color(0xE8E8E8));
            g.fillRect(x0 + 32, y, 14, 12);
            g.setColor(Color.DARK_GRAY);
            g.drawRect(x0 + 32, y, 14, 12);
            int lx = x0 + 60;
            for (int pid = 1; pid < 10; pid++) {
                g.setColor(colorFor(pid));
                g.fillRect(lx, y, 14, 12);
                g.setColor(Color.DARK_GRAY);
                g.drawRect(lx, y, 14, 12);
                g.drawString("P" + pid, lx + 16, y + 11);
                lx += 46;
                if (lx > getWidth() - 60) {
                    break;
                }
            }
        }

        private void paintDisk(Graphics2D g) {
            int cols = 16;
            int cell = Math.max(8, Math.min(getWidth() / cols - 2, (getHeight() - 40) / cols));
            int x0 = 10;
            int y0 = 26;
            for (int i = 0; i < fat.length; i++) {
                int r = i / cols;
                int cIdx = i % cols;
                int x = x0 + cIdx * cell;
                int y = y0 + r * cell;
                g.setColor(colorForBlock(i, fat[i]));
                g.fillRect(x, y, cell - 1, cell - 1);
                g.setColor(new Color(0xDDDDDD));
                g.drawRect(x, y, cell - 1, cell - 1);
            }
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            g.setColor(Color.DARK_GRAY);
            int y = y0 + 16 * cell + 14;
            g.drawString("■ 系统区(FAT/根目录)   ■ 已分配文件块   □ 空闲   ■ 坏块", x0, y);
        }

        private static Color colorForBlock(int index, int fatValue) {
            if (fatValue == DiskLayout.FAT_RESERVED) {
                return new Color(0x555555);
            }
            if (fatValue == DiskLayout.FAT_BAD) {
                return new Color(0xCC3333);
            }
            if (fatValue == DiskLayout.FAT_FREE) {
                return new Color(0xF2F2F2);
            }
            // 已分配：按块号做色相区分，便于肉眼看出链
            return Color.getHSBColor((index * 0.0618f) % 1f, 0.55f, 0.92f);
        }

        private static Color colorFor(int pid) {
            return Color.getHSBColor((pid * 0.157f) % 1f, 0.55f, 0.95f);
        }
    }

    /** 供 App 绑定：把汇编编辑器里的源码编译后保存为 .e 文件。 */
    public void compileAndSave(String path, String source) {
        Assembler.Result r = kernel.assembleAndSave(path, source);
        if (r.ok()) {
            appendLog("汇编成功：" + path + "，" + r.code().length + " 字节");
            appendLog(String.join("\n", r.listing()));
        } else {
            appendLog("汇编失败：\n" + String.join("\n", r.errors()));
        }
    }
}
