package cn.edu.scau.os.ui;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Point;
import java.util.List;

/** 无测试框架；在 EDT 上修改及检查视图，并等待延迟的光标/布局任务执行。 */
public final class TextAreaScrollRegressionTest {
    private static int assertions;
    private static Fixture fixture;

    public static void main(String[] args) throws Exception {
        List<String> lookAndFeels = List.of(UIManager.getCrossPlatformLookAndFeelClassName(),
                UIManager.getSystemLookAndFeelClassName()).stream().distinct().toList();
        for (String lookAndFeel : lookAndFeels) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    UIManager.setLookAndFeel(lookAndFeel);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            statusUpdates();
            logFollowing();
            logTrimming();
            initiallyEmpty();
        }
        System.out.println("Text area scroll regression passed: " + assertions + " assertions, "
                + lookAndFeels.size() + " look and feels, 4 groups each");
    }

    private static void statusUpdates() throws Exception {
        int[] edits = {0};
        Point[] expected = {null};
        edt(() -> {
            fixture = new Fixture(lines(0, 80, "initial"));
            fixture.area.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { edits[0]++; }
                public void removeUpdate(DocumentEvent e) { edits[0]++; }
                public void changedUpdate(DocumentEvent e) { edits[0]++; }
            });
            fixture.area.select(15, 40);
        });
        settle();
        edt(() -> {
            expected[0] = fixture.scrollTo(110, 310);
            for (int i = 0; i < 20; i++) {
                TextAreaUpdates.setText(fixture.area, lines(0, 80, "initial"));
            }
        });
        settle();
        edt(() -> {
            check(edits[0] == 0, "Identical refreshes do not rewrite the document");
            check(fixture.area.getSelectionStart() == 15 && fixture.area.getSelectionEnd() == 40,
                    "Identical refreshes preserve selection");
            fixture.checkPosition(expected[0], "Identical refreshes preserve both scroll axes");
        });

        for (int tick = 1; tick <= 25; tick++) {
            String next = lines(0, 80 + tick, "tick-" + tick);
            edt(() -> TextAreaUpdates.setText(fixture.area, next));
            settle();
            edt(() -> {
                check(fixture.area.getText().equals(next), "Changed status text is displayed");
                fixture.checkPosition(expected[0], "Changed snapshots retain the final viewport position");
            });
        }

        edt(() -> {
            fixture.scrollTo(0, fixture.maximum().y);
            expected[0] = fixture.position();
            TextAreaUpdates.setText(fixture.area, lines(0, 120, "longer"));
        });
        settle();
        edt(() -> fixture.checkPosition(expected[0], "Status text does not follow appended rows"));

        edt(() -> TextAreaUpdates.setText(fixture.area, lines(0, 10, "shorter")));
        settle();
        edt(() -> fixture.checkPosition(new Point(0, fixture.maximum().y), "Shorter text clamps vertical position"));
        edt(() -> TextAreaUpdates.setText(fixture.area, "short"));
        settle();
        edt(() -> fixture.checkPosition(new Point(), "Short text clamps both axes to zero"));
        edt(() -> TextAreaUpdates.setText(fixture.area, ""));
        settle();
        edt(() -> {
            fixture.checkPosition(new Point(), "Empty status text stays at the origin");
            TextAreaUpdates.setText(fixture.area, lines(0, 80, "restored"));
            expected[0] = fixture.scrollTo(70, 270);
        });
        settle();
        edt(() -> fixture.checkPosition(expected[0], "Later user scrolling is not overwritten by queued restoration"));
    }

    private static void logFollowing() throws Exception {
        Point[] expected = {null};
        edt(() -> {
            fixture = new Fixture(lines(0, 70, "log"));
            fixture.scrollTo(0, fixture.maximum().y);
            TextAreaUpdates.appendLog(fixture.area, "new entry");
            TextAreaUpdates.appendLog(fixture.area, "second entry\n");
        });
        settle();
        edt(() -> {
            fixture.checkPosition(new Point(0, fixture.maximum().y), "Bottom follows multiple new entries");
            check(fixture.area.getText().endsWith("new entry\nsecond entry\n"), "Log newline handling is preserved");
            expected[0] = fixture.scrollTo(0, 220);
            TextAreaUpdates.appendLog(fixture.area, "entry while reading history");
        });
        settle();
        edt(() -> {
            fixture.checkPosition(expected[0], "Reading history disables following");
            expected[0] = fixture.scrollTo(95, fixture.maximum().y);
            TextAreaUpdates.appendLog(fixture.area, "entry while reading horizontally");
        });
        settle();
        edt(() -> {
            fixture.checkPosition(expected[0], "Horizontal scrolling disables following even at the bottom");
            fixture.scrollTo(0, fixture.maximum().y);
            TextAreaUpdates.appendLog(fixture.area, "following again");
        });
        settle();
        edt(() -> fixture.checkPosition(new Point(0, fixture.maximum().y), "Returning to the bottom resumes following"));
    }

    private static void logTrimming() throws Exception {
        Point[] expected = {null};
        int[] row = {0};
        edt(() -> {
            // 999 个有内容的行和一个结尾空行；下一次追加会触发 1000 行上限。
            fixture = new Fixture(lines(0, 999, "history"));
            int height = fixture.lineHeight();
            fixture.scrollTo(80, 400 * height + 3);
            row[0] = fixture.topLine();
            int removedLines = fixture.area.getLineCount() + 1 - 800;
            expected[0] = new Point(80, fixture.position().y - removedLines * height);
            TextAreaUpdates.appendLog(fixture.area, "trim trigger");
        });
        settle();
        edt(() -> {
            check(fixture.area.getLineCount() == 800, "Log is trimmed back to 800 lines");
            fixture.checkPosition(expected[0], "Trimming compensates for removed row height and retains horizontal position");
            check(fixture.lineText(fixture.topLine()).contains("row-" + String.format("%04d", row[0])),
                    "The same retained entry stays at the top of the viewport");
        });

        edt(() -> {
            fixture = new Fixture(lines(0, 999, "history"));
            fixture.scrollTo(45, 12 * fixture.lineHeight());
            TextAreaUpdates.appendLog(fixture.area, "trim trigger");
        });
        settle();
        edt(() -> {
            fixture.checkPosition(new Point(45, 0), "Deleted reading position moves to the earliest retained entry");
            check(fixture.area.getText().startsWith("row-0201"), "Oldest entries are removed");
        });

        edt(() -> {
            fixture = new Fixture(lines(0, 999, "history"));
            fixture.scrollTo(0, fixture.maximum().y);
            TextAreaUpdates.appendLog(fixture.area, lines(999, 350, "batch"));
        });
        settle();
        edt(() -> {
            fixture.checkPosition(new Point(0, fixture.maximum().y), "Following survives trimming a multiline batch");
            check(fixture.area.getLineCount() == 800, "Large appended batches also respect the log limit");
            check(fixture.area.getText().contains("row-1348"), "Newest batch entry is retained");
        });
    }

    private static void initiallyEmpty() throws Exception {
        edt(() -> {
            fixture = new Fixture("");
            TextAreaUpdates.appendLog(fixture.area, lines(0, 60, "first"));
        });
        settle();
        edt(() -> fixture.checkPosition(new Point(0, fixture.maximum().y), "Initially empty log follows its first batch"));

        edt(() -> {
            JTextArea unattached = MainFrame.monoArea(4);
            TextAreaUpdates.setText(unattached, "status");
            TextAreaUpdates.appendLog(unattached, "message");
            check(unattached.getText().equals("statusmessage\n"), "Unattached text areas can be initialized");
        });
    }

    private static String lines(int start, int count, String value) {
        StringBuilder text = new StringBuilder();
        for (int i = start; i < start + count; i++) {
            text.append(String.format("row-%04d ", i)).append(value).append(" long column".repeat(12)).append('\n');
        }
        return text.toString();
    }

    private static void settle() throws Exception {
        edt(() -> fixture.layout());
        edt(() -> { });
        edt(() -> { });
    }

    private static void edt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Fixture {
        final JTextArea area = MainFrame.monoArea(4);
        final JScrollPane scroll;

        Fixture(String text) {
            TextAreaUpdates.setText(area, text);
            scroll = new JScrollPane(area);
            scroll.setSize(250, 115);
            layout();
        }

        void layout() {
            scroll.doLayout();
            scroll.getViewport().doLayout();
        }

        Point position() {
            return scroll.getViewport().getViewPosition();
        }

        Point maximum() {
            return new Point(Math.max(0, scroll.getViewport().getViewSize().width - scroll.getViewport().getExtentSize().width),
                    Math.max(0, scroll.getViewport().getViewSize().height - scroll.getViewport().getExtentSize().height));
        }

        Point scrollTo(int x, int y) {
            Point max = maximum();
            scroll.getViewport().setViewPosition(new Point(Math.min(x, max.x), Math.min(y, max.y)));
            return position();
        }

        int lineHeight() {
            return area.getFontMetrics(area.getFont()).getHeight();
        }

        int topLine() {
            return position().y / lineHeight();
        }

        String lineText(int line) {
            try {
                return area.getText(area.getLineStartOffset(line), area.getLineEndOffset(line) - area.getLineStartOffset(line));
            } catch (javax.swing.text.BadLocationException failure) {
                throw new AssertionError(failure);
            }
        }

        void checkPosition(Point expected, String message) {
            check(position().equals(expected), message + ": expected " + expected + ", got " + position());
        }
    }
}
