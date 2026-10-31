package cn.edu.scau.os.ui;

import javax.swing.JOptionPane;
import java.awt.Component;

/**
 * 界面里统一的确认/错误对话框。
 *
 * <p>所有危险操作的默认按钮都是「取消」，避免用户习惯性回车把磁盘清空；
 * 文案与按钮顺序集中在这里，后续新增危险操作不必各自复制 {@link JOptionPane} 调用。</p>
 */
final class Confirmations {

    private Confirmations() {
    }

    /** 危险操作确认：默认选中“取消”，返回 true 表示用户确认执行。 */
    static boolean destructive(Component parent, String title, String detail) {
        return choose(parent, title, detail, "确定执行", "取消");
    }

    /** 通用二选一：默认选中第二个（通常是取消/否），返回 true 表示选择了第一个。 */
    static boolean choose(Component parent, String title, String message, String yes, String no) {
        Object[] options = {yes, no};
        int choice = JOptionPane.showOptionDialog(parent, message, title,
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
        return choice == 0;
    }

    static void error(Component parent, String title, String message) {
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.ERROR_MESSAGE);
    }
}
