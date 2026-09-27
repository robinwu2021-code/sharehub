package ai.neargo.opskit.commons.timer;

import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import java.util.Objects;

/**
 * 定时任务 ID：{@code 空间:键:epoch}。任务不带数据，处理器全靠它定位对象（02 §九）；
 * epoch 与库里的当前值不符即说明这个任务已被重排作废，处理器直接无操作。
 *
 * <p>db-scheduler 的 task_instance 列宽 100：空间 ≤ 32 + 键 ≤ 40 + epoch ≤ 10 + 两个冒号，留有余量。
 */
public record TimerId(WorkspaceId workspace, String key, int epoch) {

    private static final int MAX_KEY = 40;

    public TimerId {
        Objects.requireNonNull(workspace, "workspace");
        if (workspace.isTemplate()) {
            throw new IllegalArgumentException("timers belong to a concrete workspace");
        }
        if (key == null || key.isEmpty() || key.length() > MAX_KEY || key.indexOf(':') >= 0) {
            throw new IllegalArgumentException("timer key must be 1.." + MAX_KEY + " chars without ':': " + key);
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must be >= 0");
        }
    }

    public String encode() {
        return workspace.value() + ":" + key + ":" + epoch;
    }

    public static TimerId decode(String text) {
        String[] parts = text.split(":", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("timer id must be ws:key:epoch, got: " + text);
        }
        return new TimerId(new WorkspaceId(parts[0]), parts[1], Integer.parseInt(parts[2]));
    }
}
