package cn.edu.scau.os.ui;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.awt.Dimension;
import java.awt.Point;

/** 主窗口只读文本的刷新策略；所有操作都由 Swing EDT 调用。 */
final class TextAreaUpdates {
    private TextAreaUpdates() { }

    static void setText(JTextArea area, String text) {
        if (text.equals(area.getText())) {
            return;
        }
        ViewState state = ViewState.capture(area);
        area.setText(text);
        state.restore(false, 0);
    }

    static void appendLog(JTextArea area, String text) {
        ViewState state = ViewState.capture(area);
        area.append(text.endsWith("\n") ? text : text + "\n");
        int removedLines = 0;
        if (area.getLineCount() > 1000) {
            removedLines = area.getLineCount() - 800;
            try {
                area.replaceRange("", 0, area.getLineEndOffset(removedLines - 1));
            } catch (BadLocationException failure) {
                throw new IllegalStateException("无法裁剪运行日志", failure);
            }
        }
        // 主窗口日志不折行，每删一行需向上补偿一个行高，保持正在阅读的内容。
        int removedHeight = removedLines * area.getFontMetrics(area.getFont()).getHeight();
        state.restore(state.atBottom, removedHeight);
    }

    private static final class ViewState {
        private final JViewport viewport;
        private final Point position;
        private final boolean atBottom;

        private ViewState(JViewport viewport) {
            this.viewport = viewport;
            position = viewport == null ? new Point() : viewport.getViewPosition();
            atBottom = viewport != null && position.x == 0
                    && position.y + viewport.getExtentSize().height >= viewport.getViewSize().height - 1;
        }

        static ViewState capture(JTextArea area) {
            return new ViewState(area.getParent() instanceof JViewport viewport ? viewport : null);
        }

        void restore(boolean followBottom, int removedHeight) {
            if (viewport == null) {
                return;
            }
            // setText/append 会异步触发布局；先同步计算新的内容和滚动条范围。
            // 不排队恢复旧位置，避免覆盖用户在下一轮 EDT 中的滚动操作。
            JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, viewport);
            if (scroll != null) {
                scroll.doLayout();
            }
            viewport.doLayout();
            Dimension size = viewport.getViewSize();
            Dimension extent = viewport.getExtentSize();
            int maxX = Math.max(0, size.width - extent.width);
            int maxY = Math.max(0, size.height - extent.height);
            viewport.setViewPosition(new Point(Math.min(position.x, maxX), followBottom
                    ? maxY : Math.min(Math.max(0, position.y - removedHeight), maxY)));
        }
    }
}
