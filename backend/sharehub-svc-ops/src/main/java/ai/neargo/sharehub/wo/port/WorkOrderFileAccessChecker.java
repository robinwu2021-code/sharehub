package ai.neargo.sharehub.wo.port;

import ai.neargo.sharehub.api.platform.port.FileAccessChecker;
import ai.neargo.sharehub.wo.entity.WoOrder;
import ai.neargo.sharehub.wo.mapper.WoMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

/** 工单现场照片的下载鉴权：能按数据范围读到这张工单，就能看它的照片（代理只看得到自己站点的单）。 */
@Component
public class WorkOrderFileAccessChecker implements FileAccessChecker {

    private final WoMapper orders;

    public WorkOrderFileAccessChecker(WoMapper orders) {
        this.orders = orders;
    }

    @Override
    public String bizType() {
        return "WORK_ORDER";
    }

    @Override
    public boolean canRead(String bizNo) {
        // 带数据范围的普通查询：范围外即查不到 ⇒ 无权（与「不存在」同一结果）
        return orders.selectCount(new LambdaQueryWrapper<WoOrder>().eq(WoOrder::getWoNo, bizNo)) > 0;
    }
}
