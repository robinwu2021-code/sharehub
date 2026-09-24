package ai.neargo.sharehub.platform.notify.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.platform.notify.NotifyTargets;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyBlacklistVO;
import ai.neargo.sharehub.platform.notify.entity.NotifyBlacklist;
import ai.neargo.sharehub.platform.notify.mapper.NotifyBlacklistMapper;
import ai.neargo.sharehub.platform.notify.service.NotifyBlacklistService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 触达拉黑实现。手写而非套 CRUD 基类，因为它有两条真实业务规则：
 * <b>解除是状态流转（软删）</b>、<b>命中判定要处理 {@code ALL} 与到期</b>。
 */
@Service
public class NotifyBlacklistServiceImpl implements NotifyBlacklistService {

    private static final String TENANT_MAIN = "MAIN";
    private static final String CHANNEL_ALL = "ALL";
    private static final String ACTIVE = "ACTIVE";
    private static final String RELEASED = "RELEASED";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotifyBlacklistMapper mapper;

    public NotifyBlacklistServiceImpl(NotifyBlacklistMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<NotifyBlacklistVO> page(Integer page, Integer size, String keyword,
                                              String channel, String reason, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<NotifyBlacklist> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = NotifyTargets.maskTarget(keyword.trim()); // 表里是脱敏值，明文关键词先同口径化
            w.and(q -> q.like(NotifyBlacklist::getBlockNo, keyword.trim())
                    .or().like(NotifyBlacklist::getTarget, kw));
        }
        if (channel != null && !channel.isBlank()) w.eq(NotifyBlacklist::getChannel, channel);
        if (reason != null && !reason.isBlank()) w.eq(NotifyBlacklist::getReason, reason);
        if (status != null && !status.isBlank()) w.eq(NotifyBlacklist::getStatus, status);
        w.orderByDesc(NotifyBlacklist::getId);

        Page<NotifyBlacklist> r = mapper.selectPage(new Page<>(p, s), w);
        List<NotifyBlacklistVO> rows = r.getRecords().stream().map(NotifyBlacklistServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public NotifyBlacklistVO block(NotifyBlacklist body) {
        if (body.getTarget() == null || body.getTarget().isBlank()) {
            throw new IllegalArgumentException("target 必填");
        }
        body.setTenantId(TENANT_MAIN);
        body.setTarget(NotifyTargets.maskTarget(body.getTarget())); // 脱敏存储，与 notify_log 同口径
        if (body.getChannel() == null || body.getChannel().isBlank()) body.setChannel(CHANNEL_ALL);
        if (body.getReason() == null || body.getReason().isBlank()) body.setReason("MANUAL");
        if (body.getBlockedAt() == null || body.getBlockedAt().isBlank()) body.setBlockedAt(now());
        body.setStatus(ACTIVE);
        body.setReleasedAt(null);
        body.setReleasedBy(null);
        if (body.getBlockNo() == null || body.getBlockNo().isBlank()) body.setBlockNo(nextBlockNo());
        mapper.insert(body);
        return toVO(body);
    }

    @Override
    public NotifyBlacklistVO update(String blockNo, NotifyBlacklist body) {
        NotifyBlacklist e = selectByNo(blockNo);
        if (e == null) throw new IllegalArgumentException("拉黑记录不存在: " + blockNo);
        if (RELEASED.equals(e.getStatus())) {
            // 已解除的是历史记录：解除动作是对当时那份内容做的，事后改内容就对不上了
            throw new IllegalArgumentException("已解除的拉黑记录不可修改: " + blockNo);
        }
        // target 不受理：它是脱敏存的，改掉等于换了一个人被拉黑，而从掩码上看不出换没换。
        // 拉黑别人请新建一条 —— 那样才留得下「谁在什么时候被拉黑」这条痕。
        if (body.getChannel() != null && !body.getChannel().isBlank()) e.setChannel(body.getChannel());
        if (body.getReason() != null && !body.getReason().isBlank()) e.setReason(body.getReason());
        mapper.updateById(e);
        return toVO(e);
    }

    @Override
    public NotifyBlacklistVO release(String blockNo, String operator) {
        NotifyBlacklist e = selectByNo(blockNo);
        if (e == null) throw new IllegalArgumentException("拉黑记录不存在: " + blockNo);
        if (RELEASED.equals(e.getStatus())) {
            throw new IllegalArgumentException("已解除，不能重复解除: " + blockNo); // 非法流转
        }
        // 软删：记录留着，只翻状态 + 留痕。合规要能回答"何时被谁拉黑、又何时被谁放开"
        e.setStatus(RELEASED);
        e.setReleasedAt(now());
        e.setReleasedBy(operator == null || operator.isBlank() ? "SYSTEM" : operator);
        mapper.updateById(e);
        return toVO(e);
    }

    @Override
    public boolean isBlocked(String target, String channel) {
        if (target == null || target.isBlank()) return false;
        String masked = NotifyTargets.maskTarget(target);
        String ch = (channel == null || channel.isBlank()) ? CHANNEL_ALL : channel;
        String nowTs = now();

        QueryWrapper<NotifyBlacklist> w = new QueryWrapper<>();
        w.eq("target", masked)
                .eq("status", ACTIVE)
                // channel=ALL 的记录命中任意渠道；否则精确相等
                .and(q -> q.eq("channel", ch).or().eq("channel", CHANNEL_ALL))
                // 空 expire_at = 永久拉黑；有值则需未过期
                .and(q -> q.isNull("expire_at").or().gt("expire_at", nowTs))
                .last("limit 1");
        return mapper.selectCount(w) > 0;
    }

    private NotifyBlacklist selectByNo(String blockNo) {
        if (blockNo == null || blockNo.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<NotifyBlacklist>()
                .eq(NotifyBlacklist::getBlockNo, blockNo).last("limit 1"));
    }

    /** 取号：扫同前缀最大号 +1（前缀 {@code NBL}，勿与用户黑名单 {@code BL} 混用）。 */
    private String nextBlockNo() {
        NotifyBlacklist top = mapper.selectOne(new QueryWrapper<NotifyBlacklist>()
                .likeRight("block_no", BizKey.NOTIFY_BLACKLIST)
                .orderByDesc("block_no")
                .last("limit 1"));
        long n = 0L;
        if (top != null && top.getBlockNo() != null
                && top.getBlockNo().length() > BizKey.NOTIFY_BLACKLIST.length()) {
            String digits = top.getBlockNo().substring(BizKey.NOTIFY_BLACKLIST.length()).replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                try {
                    n = Long.parseLong(digits);
                } catch (NumberFormatException ignore) {
                    // 历史脏号不参与取号，UK 兜底
                }
            }
        }
        return BizKey.NOTIFY_BLACKLIST + String.format("%04d", n + 1);
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    private static NotifyBlacklistVO toVO(NotifyBlacklist e) {
        return new NotifyBlacklistVO(e.getBlockNo(), e.getTarget(), e.getChannel(), e.getReason(),
                e.getBlockedAt(), e.getBlockedBy(), e.getExpireAt(),
                e.getReleasedAt(), e.getReleasedBy(), e.getStatus());
    }
}
