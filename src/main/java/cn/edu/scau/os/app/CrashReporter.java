package cn.edu.scau.os.app;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全局未捕获异常兜底：把堆栈写入日志，并按频率弹出提示。
 *
 * <p>三条硬约束：</p>
 * <ol>
 *   <li>处理器自己绝不抛异常，否则可能二次崩溃或进入死循环；</li>
 *   <li>无界面（--selftest / 回归测试）时只写日志，不弹窗，避免测试卡住；</li>
 *   <li>同一异常特征 5 秒内只弹一次，避免 200ms 定时器把界面刷满对话框。</li>
 * </ol>
 *
 * <p>该类不依赖任何业务类，可以最先安装。</p>
 */
public final class CrashReporter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /** 单个日志文件上限，超过后轮转为 errors.log.1。 */
    private static final long MAX_LOG_BYTES = 1L << 20;
    /** 同一异常在这么长时间内只弹一次对话框。 */
    private static final long DIALOG_GAP_MILLIS = 5_000L;
    /** 对话框里最多显示这么多字符，完整内容在日志文件里。 */
    private static final int DIALOG_TEXT_LIMIT = 8_000;
    private static final Object LOCK = new Object();

    private static Path logFile = Path.of("runtime", "errors.log");
    private static long lastDialogAt;
    private static String lastSignature = "";

    private CrashReporter() {
    }

    /** 安装全局未捕获异常处理器；{@code file} 为 null 时使用默认的 runtime/errors.log。 */
    public static void install(Path file) {
        if (file != null) {
            logFile = file;
        }
        Thread.setDefaultUncaughtExceptionHandler(
                (thread, failure) -> report("线程「" + thread.getName() + "」未捕获异常", failure));
    }

    /** 只写日志并输出到 stderr，不弹窗（调用方自己负责提示时使用）。 */
    public static void record(String context, Throwable failure) {
        String text = describe(context, failure);
        synchronized (LOCK) {
            appendLog(text);
        }
        System.err.print(text);
    }

    /** 写日志并按需弹窗（headless 或短时间内重复的异常不弹窗）。 */
    public static void report(String context, Throwable failure) {
        String text = describe(context, failure);
        synchronized (LOCK) {
            appendLog(text);
        }
        System.err.print(text);
        if (GraphicsEnvironment.isHeadless() || !throttleAllows(failure)) {
            return;
        }
        showDialog(context + "：" + rootMessage(failure), text);
    }

    /** 异常的"指纹"：类型 + 首个栈帧，用于限频。 */
    private static String signature(Throwable failure) {
        if (failure == null) {
            return "null";
        }
        StackTraceElement[] stack = failure.getStackTrace();
        String frame = stack.length == 0 ? "?" : stack[0].toString();
        return failure.getClass().getName() + "@" + frame;
    }

    private static boolean throttleAllows(Throwable failure) {
        String signature = signature(failure);
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (signature.equals(lastSignature) && now - lastDialogAt < DIALOG_GAP_MILLIS) {
                return false;
            }
            lastSignature = signature;
            lastDialogAt = now;
            return true;
        }
    }

    private static String describe(String context, Throwable failure) {
        StringWriter buffer = new StringWriter();
        failure.printStackTrace(new PrintWriter(buffer));
        return "[" + LocalDateTime.now().format(TIME) + "] " + context
                + System.lineSeparator() + buffer;
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    /** 追加日志；任何失败都只提示到 stderr，绝不再抛。调用方必须持有 LOCK。 */
    private static void appendLog(String text) {
        try {
            Path absolute = logFile.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            rotateIfNeeded(absolute);
            Files.writeString(absolute, text + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (Throwable ignored) {
            System.err.println("CrashReporter: 无法写入日志 " + logFile);
        }
    }

    private static void rotateIfNeeded(Path absolute) throws IOException {
        if (!Files.exists(absolute) || Files.size(absolute) <= MAX_LOG_BYTES) {
            return;
        }
        Path rotated = absolute.resolveSibling(absolute.getFileName() + ".1");
        Files.move(absolute, rotated, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void showDialog(String summary, String detail) {
        SwingUtilities.invokeLater(() -> {
            try {
                JTextArea area = new JTextArea(truncate(detail));
                area.setEditable(false);
                area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                area.setCaretPosition(0);
                JScrollPane scroll = new JScrollPane(area);
                scroll.setPreferredSize(new Dimension(760, 340));
                JOptionPane.showMessageDialog(null, new Object[]{summary, scroll},
                        "SimOS 发生未处理的错误", JOptionPane.ERROR_MESSAGE);
            } catch (Throwable ignored) {
                // 弹窗失败也不能再抛
            }
        });
    }

    private static String truncate(String text) {
        if (text.length() <= DIALOG_TEXT_LIMIT) {
            return text;
        }
        return text.substring(0, DIALOG_TEXT_LIMIT) + "\n……（完整堆栈见日志文件）";
    }
}
