package ai.neargo.sharehub.agent.apply.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 运营主体入驻申请（{@code agt_apply}，ADR-030 §三）。
 *
 * <p>用户 2026-09-23 定：「商家可以注册，运营商也可以代建，<b>条件相同</b>，商家注册，也需要运营商审核通过。」
 * 「条件相同」在这张表上的含义是 <b>不按 {@link #source} 分叉</b>：
 * 一张表、一个状态机、一套必填校验（必填由 {@link #operatorType} 决定，<b>不由 source 决定</b>）。
 *
 * <p>业务键前缀 {@code AP}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agt_apply")
public class AgtApply extends BaseEntity {

    private String applyNo;

    /**
     * {@code SELF_SERVICE} 商家自助 / {@code OPS_CREATED} 运营商代建。
     *
     * <p><b>由服务端按有无 STAFF 令牌判定，客户端不得传</b> —— 否则自助申请可以自称代建，
     * 绕开自助那侧的 OTP 与限流。
     */
    private String source;

    /*
     * ⚠️ phoneEnc / emailEnc 两列**全仓没有任何一处写入**，生产上恒为 NULL。
     * 设计上它们是「需要还原时（发短信/邮件）」与「pepper 轮换时重算 hash 的依据」，
     * 两个用途都没有落过地。要联系方式请看下面的 contact* 三列 ——
     * 那才是运营真正要看见、要拨打的东西（V127）。
     */
    private String phoneHash;
    private String phoneMask;
    private byte[] phoneEnc;
    private String emailHash;
    private String emailMask;
    private byte[] emailEnc;
    private Integer hashVer;

    /** 命中已有自然人时回填；空 = 新人。<b>手机号已存在不是重复注册，是多主体申请</b>。 */
    private String principalNo;

    private String operatorName;

    /** AGENT / CITY_PARTNER。**决定必填项**。 */
    private String operatorType;

    /*
     * ——— 业务联系方式（明文，V127）———
     *
     * 与上面的身份三列**语义不同，不要混用**：
     *   · phoneHash/Mask 是**登录标识** —— 要能查找、要唯一、不可逆；
     *   · 这三列是**运营要拨打、要发件的联系方式** —— 本就该是明文，
     *     而且联系人完全可能不是那个登录的自然人（换对接人是常事）。
     *
     * contactPhone/Email 默认取提交时填的那个号（归一化后），不让人填两遍；
     * 审核通过时回填到 agt_agent 的同名列，运营可在档案里单独改。
     */
    private String contactName;
    private String contactPhone;
    private String contactEmail;

    private String regionScope;

    private java.math.BigDecimal shareRate;

    /** 资质材料 JSON；敏感件只存 pii 引用，不落明文。 */
    private String payload;

    /** DRAFT / SUBMITTED / REVIEWING / APPROVED / REJECTED。 */
    private String status;

    /** 驳回原因。**原样回显给申请人**，不是只给运营看。 */
    private String rejectReason;

    private String submittedBy;
    private LocalDateTime submittedAt;

    /** 审核人。代建一键通过时与 {@link #submittedBy} 同值，**照写不省** —— 审计链不能断。 */
    private String reviewedBy;
    private LocalDateTime reviewedAt;

    /** 通过后回写，申请 ↔ 主体双向可查。 */
    private String operatorNo;

    /**
     * 生成列（DB 维护）：在途取 {@code phoneHash}、终态取 {@code applyNo}。
     *
     * <p>它让「同一手机号至多一张在途申请」这条约束<b>全程非空</b>地成立 ——
     * 直觉写法「终态置 NULL + 可空唯一键」在 MySQL/MariaDB 下等于没有约束
     * （UNIQUE 不约束 NULL），这正是 {@code share_record} 幂等键失效的同一个根因。
     */
    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String activeKey;
}
