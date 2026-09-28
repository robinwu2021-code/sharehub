package ai.neargo.sharehub.cs.port;

import ai.neargo.sharehub.api.platform.port.FileAccessChecker;
import ai.neargo.sharehub.auth.Realm;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.cs.entity.CsTicket;
import ai.neargo.sharehub.cs.mapper.CsTicketMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

/**
 * 报障截图的下载鉴权（{@code CS_TICKET}）。
 *
 * <p>此前没有这个检查器：{@code FileServiceImpl#requireReadable} 找不到 bizType 对应的检查器时
 * 一律当「不存在」拒绝 —— 图传上去了、详情里也列得出来，却**谁都下载不了**，
 * 运营看到的是一行文件名，点开是 400。
 *
 * <p>两种主体两套判法：
 * <ul>
 *   <li><b>C 端</b>：只能看自己那张单的图（属主比对，与 {@code getForUser} 同一口径）；</li>
 *   <li><b>运营</b>：按数据范围能查到这张单就能看（范围外查不到 ⇒ 无权，与「不存在」同一结果）。</li>
 * </ul>
 */
@Component
public class CsTicketFileAccessChecker implements FileAccessChecker {

    private final CsTicketMapper tickets;

    public CsTicketFileAccessChecker(CsTicketMapper tickets) {
        this.tickets = tickets;
    }

    @Override
    public String bizType() {
        return "CS_TICKET";
    }

    @Override
    public boolean canRead(String bizNo) {
        LambdaQueryWrapper<CsTicket> w = new LambdaQueryWrapper<CsTicket>().eq(CsTicket::getTicketNo, bizNo);
        if (SecurityUtils.realm() == Realm.CONSUMER) {
            // 属主过滤写死在这里，不看调用方传了什么
            w.eq(CsTicket::getCUserNo, SecurityUtils.userNo());
        }
        return tickets.selectCount(w) > 0;
    }
}
