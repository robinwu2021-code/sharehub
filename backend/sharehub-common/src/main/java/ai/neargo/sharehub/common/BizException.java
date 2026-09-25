package ai.neargo.sharehub.common;

import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.ServerException;

/**
 * 业务异常 —— {@link ServerException} 加上 <b>i18n 参数</b>。
 *
 * <h2>为什么非得新加一个类型</h2>
 * 后端的错误文案要按 {@code Accept-Language} 出三语（[i18n 方案]），做法是 message 传 <b>消息 key</b>，
 * 在边界由 {@link Messages} 解析。但真实的业务文案大多带值 ——
 * 「仅 FROZEN 可请款，当前：RELEASED」「找不到指定记录：PB000123」——
 * 而 {@code ServerException} 只有 {@code (ErrorCode, String)} 构造器、<b>带不了参数</b>，
 * 它在 neargo-common-core 里，改不了。于是只能在本仓库加一层。
 *
 * <p>2026-09-25 实测：22 处业务拒绝里 20 处带插值 —— 不解决这个，i18n 就只能覆盖那 2 条纯字面量的。
 *
 * <h2>怎么用</h2>
 * <pre>
 *   throw BizException.conflict("error.coupon.sold_out", tplNo);     // → 409
 *   throw BizException.notFound(powerbankNo);                        // → 400，通用「找不到指定记录：{0}」
 * </pre>
 * message 位置传的是 <b>key</b>；key 不存在时 {@code Messages} 原样返回它本身
 * （{@code useCodeAsDefaultMessage=true}），所以写错 key 的表现是界面上出现一串
 * {@code error.xxx} —— 难看，但不会抛异常、也不会把中文写死回去。
 */
public class BizException extends ServerException {

    /** 通用「找不到」：{@code {0}} 是调用方自己传进来的那个业务键。 */
    public static final String NOT_FOUND = "error.common.not_found";

    /**
     * i18n 占位参数。
     *
     * <p>{@code transient}：{@link ServerException} 可被序列化，而这里装的是任意业务对象，
     * 不保证可序列化 —— 真被序列化时宁可丢参数，也不要在异常传播路上再抛一个异常。
     */
    private final transient Object[] args;

    private BizException(ErrorCode code, String messageKey, Object... args) {
        super(code, messageKey);
        this.args = args == null ? new Object[0] : args.clone();
    }

    public static BizException of(ErrorCode code, String messageKey, Object... args) {
        return new BizException(code, messageKey, args);
    }

    /** 状态冲突（409）：调用方换个做法就能成功，不需要任何人介入。 */
    public static BizException conflict(String messageKey, Object... args) {
        return new BizException(ErrorCode.CONFLICT, messageKey, args);
    }

    /** 入参有问题（400）。 */
    public static BizException badRequest(String messageKey, Object... args) {
        return new BizException(ErrorCode.BAD_REQUEST, messageKey, args);
    }

    /**
     * 找不到指定记录（400）。
     *
     * <p><b>不带实体名</b>：87 处「xxx不存在: 键」原本每处一句中文实体名
     * （「拉黑记录」「巡检计划」「适用范围」…），逐条翻译就是 87 × 3 个名词。
     * 而调用方本来就知道自己在操作什么 —— 页面/抽屉的上下文已经说了，
     * 回一句带业务键的通用文案就够，多出来的那个名词只服务于写代码的人。
     * 需要具体名词的地方仍可以用 {@link #badRequest} 传自己的 key。
     *
     * @param bizNo 调用方传进来的那个业务键；回显它不泄露任何东西，本来就是他给的
     */
    public static BizException notFound(Object bizNo) {
        return new BizException(ErrorCode.BAD_REQUEST, NOT_FOUND, bizNo);
    }

    public Object[] args() {
        return args.clone();
    }
}
