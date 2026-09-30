package cn.edu.scau.os.process;

/** 阻塞原因，用于界面上"阻塞队列及等待时间"一栏。 */
public enum BlockReason {
    NONE("—"),
    DEVICE("等待设备"),
    MEMORY("等待内存"),
    IDLE("闲逛");

    private final String text;

    BlockReason(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
