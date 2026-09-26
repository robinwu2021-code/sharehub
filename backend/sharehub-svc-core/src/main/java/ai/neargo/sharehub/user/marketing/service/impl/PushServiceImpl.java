package ai.neargo.sharehub.user.marketing.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.user.marketing.AudienceType;
import ai.neargo.sharehub.user.marketing.PushStatus;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.PushMessageVO;
import ai.neargo.sharehub.user.marketing.entity.MktPush;
import ai.neargo.sharehub.user.marketing.mapper.MktPushMapper;
import ai.neargo.sharehub.user.marketing.service.PushService;
import org.springframework.stereotype.Service;

/** 推送触达实现。 */
@Service
public class PushServiceImpl extends AbstractCrudService<MktPush, PushMessageVO> implements PushService {

    public PushServiceImpl(MktPushMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "push_no";
    }

    @Override
    protected String keyOf(MktPush e) {
        return e.getPushNo();
    }

    @Override
    protected void setKey(MktPush e, String no) {
        e.setPushNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.PUSH_MESSAGE;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"push_no", "title"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"channel", "status"};
    }

    @Override
    protected void beforeCreate(MktPush e) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus(PushStatus.DRAFT.name());
        if (e.getChannel() == null || e.getChannel().isBlank()) e.setChannel("APP_PUSH");
        if (e.getSentCount() == null) e.setSentCount(0);
        if (e.getTargetCount() == null) e.setTargetCount(0);
        if (e.getSuccessCount() == null) e.setSuccessCount(0);
        if (e.getAudienceType() == null || e.getAudienceType().isBlank()) e.setAudienceType(AudienceType.ALL.name());
        e.setAudience(audienceLabel(e.getAudienceType(), e.getAudienceValue()));
    }

    /**
     * 人群的可读标签由服务端拼，**不让前端自己解** —— 两边各拼一次必然出现
     * 列表上写「全部用户」而详情写「全量」这种不一致，且没人会认为它是 bug。
     */
    private static String audienceLabel(String type, String value) {
        if (type == null) return "全部用户";
        // case 上用枚举常量而不是字面量：打错字不会报错，只会静默落到 default，
        // 于是「发给会员等级 GOLD」在列表上显示成「全部用户」——**看起来完全正常**。
        return switch (AudienceType.of(type).orElse(AudienceType.ALL)) {
            case MEMBER_LEVEL -> "会员等级 " + (value == null || value.isBlank() ? "-" : value);
            case SEGMENT -> "分群 " + (value == null || value.isBlank() ? "-" : value);
            // 用户号列表可能很长，标签只报个数，真值在 audienceValue 里
            case USER_LIST -> "指定用户 " + (value == null || value.isBlank()
                    ? "0" : String.valueOf(value.split(",").length)) + " 人";
            case ALL -> "全部用户";
        };
    }

    @Override
    protected void beforeUpdate(MktPush e, MktPush current) {
        /*
         * **状态不接受客户端传入** —— 只能由 /schedule · /send · /finish 走状态机改。
         *
         * 实体就是请求体（见 known-entity-request-bodies.txt），而 MyBatis-Plus 的
         * updateById 只写非 null 字段 —— 不锁的话保存端点就是绕过状态机的第二条路：
         *   POST /api/user/push-messages/{pushNo}  {"status":"SENT"}
         * 草稿直接变「已发送」：既没真发、也没有发送记录，而列表上白纸黑字写着已发送。
         * 下面那个 `SENT 之后不可改内容` 的守卫也会被它反过来利用 ——
         * 先把状态刷成 SENT，内容就此冻结成假的。
         *
         * 此前这里只在 current 已经是 SENT 时才回填状态，DRAFT/SCHEDULED/SENDING 一律放行。
         */
        e.setStatus(current.getStatus());

        // 已下发的推送不可再改内容/受众 —— 否则历史触达记录与本单不自洽
        if (PushStatus.SENT.is(current.getStatus())) {
            e.setTitle(current.getTitle());
            e.setContent(current.getContent());
            e.setAudience(current.getAudience());
            e.setChannel(current.getChannel());
            e.setSentCount(current.getSentCount());
            e.setSentAt(current.getSentAt());
        }
        /*
         * 触达统计与幂等键**一律从库里取**，不接受客户端传 ——
         * 实体就是请求体（见 known-entity-request-bodies.txt），
         * 不锁的话任何人都能把「成功 3 人」改成「成功 30000 人」，
         * 而这三个数正是运营用来判断触达效果的。
         */
        e.setTargetCount(current.getTargetCount());
        e.setSuccessCount(current.getSuccessCount());
        e.setIdempotencyKey(current.getIdempotencyKey());
        e.setOperatorName(current.getOperatorName());
        // 人群改了要重算标签，否则标签停在旧人群上
        e.setAudience(audienceLabel(e.getAudienceType(), e.getAudienceValue()));
    }

    @Override
    protected PushMessageVO toVO(MktPush e) {
        return new PushMessageVO(e.getPushNo(), e.getTitle(), e.getContent(), e.getChannel(),
                e.getAudience(), e.getAudienceType(), e.getAudienceValue(),
                e.getTargetCount(), e.getSuccessCount(), e.getSentCount(),
                e.getStatus(), e.getScheduledAt(), e.getSentAt(),
                e.getOperatorName(), e.getIdempotencyKey());
    }

    /*
     * 状态机（与前端 PUSH_TRANSITIONS 逐条对齐）：
     *   DRAFT --schedule--> SCHEDULED
     *   DRAFT | SCHEDULED --send--> SENDING
     *   SENDING --finish--> SENT      （**终态，不可重发**）
     * 非法迁移一律抛错而不是静默放行 —— 静默放行会让「已发送的单子又回到草稿」
     * 这种事发生了也没人发现。
     */
    private static void requireStatus(MktPush e, String action, String... allowed) {
        for (String a : allowed) {
            if (a.equals(e.getStatus())) return;
        }
        throw new IllegalArgumentException(
                "推送 " + e.getPushNo() + " 当前是 " + e.getStatus() + "，不能" + action);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Object schedule(String pushNo, String scheduledAt, String operatorName) {
        if (scheduledAt == null || scheduledAt.isBlank()) {
            throw new IllegalArgumentException("排期时间必填");
        }
        MktPush e = selectByKey(pushNo);
        if (e == null) throw BizException.notFound(pushNo);
        requireStatus(e, "排期", PushStatus.DRAFT.name());
        e.setStatus(PushStatus.SCHEDULED.name());
        e.setScheduledAt(scheduledAt);
        // 操作人：**传参只用于系统重放**（sweepDue 沿用排期时记下的那位），
        // 用户动作一律传 null 由会话决定 —— 此前控制器从请求体读 operatorName，
        // 而这里「有传参就用传参」，等于 push_message.operator_name 由调用方随便写；
        // 更糟的是没有会话兜底，不传就是 null，事后连"谁发的"都答不上来。
        e.setOperatorName(operatorName != null && !operatorName.isBlank() ? operatorName
                : ai.neargo.sharehub.auth.SecurityUtils.currentUser()
                        .map(ai.neargo.sharehub.auth.LoginUser::username).orElse("system"));
        mapper.updateById(e);
        return toVO(selectByKey(pushNo));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Object send(String pushNo, String idempotencyKey, String operatorName) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // 推送是真推到用户手机上，双击不该推两次。不给键就拒，不"帮它生成"——
            // 生成的话双击会得到两个不同的键，幂等形同虚设。
            throw new IllegalArgumentException("发送推送必须携带 idempotencyKey");
        }
        MktPush e = selectByKey(pushNo);
        if (e == null) throw BizException.notFound(pushNo);

        /*
         * **幂等按键判，不按状态判。**
         * 原先的写法是「已 SENT 就返回」，两个并发的首次发送会同时看到非 SENT、
         * 然后都发出去。真正兜住的是 V75 的 uk_push_idem 唯一索引 ——
         * 这里先查一次只是为了给重放一个干净的 200，而不是靠它保证唯一。
         */
        if (idempotencyKey.equals(e.getIdempotencyKey())) return toVO(e);

        requireStatus(e, "发送", PushStatus.DRAFT.name(), PushStatus.SCHEDULED.name());
        e.setStatus(PushStatus.SENDING.name());
        e.setIdempotencyKey(idempotencyKey);
        // 同 schedule：传参只用于系统重放，用户动作走会话
        e.setOperatorName(operatorName != null && !operatorName.isBlank() ? operatorName
                : ai.neargo.sharehub.auth.SecurityUtils.currentUser()
                        .map(ai.neargo.sharehub.auth.LoginUser::username).orElse("system"));
        e.setSentAt(java.time.Instant.now().toString());
        mapper.updateById(e);
        return toVO(selectByKey(pushNo));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Object finish(String pushNo, Integer targetCount, Integer successCount) {
        MktPush e = selectByKey(pushNo);
        if (e == null) throw BizException.notFound(pushNo);
        if (PushStatus.SENT.is(e.getStatus())) return toVO(e);   // 重复收尾按幂等处理
        requireStatus(e, "收尾", PushStatus.SENDING.name());
        int t = targetCount == null ? 0 : targetCount;
        int ok = successCount == null ? 0 : successCount;
        if (ok > t) {
            // 成功数不可能超过目标数。放过去的话页面上会出现「目标 10 人 / 成功 30 人」，
            // 而看到的人只会以为是显示错了。
            throw new IllegalArgumentException("成功触达数不能大于目标人数");
        }
        e.setStatus(PushStatus.SENT.name());
        e.setTargetCount(t);
        e.setSuccessCount(ok);
        e.setSentCount(ok);      // 兼容既有列表列「触达数」，与 successCount 同值
        mapper.updateById(e);
        return toVO(selectByKey(pushNo));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int sweepDue(String now) {
        String at = (now == null || now.isBlank()) ? java.time.Instant.now().toString() : now;
        java.util.List<MktPush> due = mapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MktPush>()
                        .eq("status", PushStatus.SCHEDULED.name())
                        .le("scheduled_at", at));
        int n = 0;
        for (MktPush e : due) {
            /*
             * 幂等键由「单号 + 排期时刻」派生：同一张单的同一次排期，
             * 扫描跑多少轮都只会发一次（唯一索引兜底）。
             * 用随机键的话，扫描重跑就等于重发 —— 而扫描重跑是常态（重启、补偿、手动点）。
             */
            // 排期发出的操作人沿用排期时记下的那位，不改写成"系统"——
            // 运营要能回答"这条是谁安排的"，而扫描只是替他按了发送。
            send(e.getPushNo(), "sched:" + e.getPushNo() + ":" + e.getScheduledAt(), e.getOperatorName());
            n++;
        }
        return n;
    }
}
