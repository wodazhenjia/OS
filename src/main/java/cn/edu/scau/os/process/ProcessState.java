package cn.edu.scau.os.process;

/** 进程状态。题目六只用到"就绪 / 运行 / 阻塞"三种，加上"空闲 PCB"这一管理态。 */
public enum ProcessState {
    FREE("空闲PCB"),
    READY("就绪"),
    RUNNING("运行"),
    BLOCKED("阻塞");

    private final String text;

    ProcessState(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
