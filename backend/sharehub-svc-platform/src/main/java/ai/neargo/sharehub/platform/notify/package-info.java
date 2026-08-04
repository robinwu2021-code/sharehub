/**
 * 消息触达域（{@code notify_template} / {@code notify_log} / {@code notify_blacklist}）
 * —— [db-design §2.3]、[api/README §7.3]。
 *
 * <p>本包有三条不可绕开的规则，读代码前先记住：
 * <ol>
 *   <li><b>发送前必查黑名单</b>：{@code NotifySendService} 是发送的唯一正门，
 *       各业务域一律经 {@code POST /internal/platform/notify/send}，不得直连渠道商 ——
 *       否则退订会被绕过，那是实打实的合规处罚项。</li>
 *   <li><b>target 存储即脱敏</b>：明文联系方式不入 {@code notify_log}/{@code notify_blacklist}，
 *       写库前过 {@link ai.neargo.sharehub.platform.notify.NotifyTargets#maskTarget}。</li>
 *   <li><b>解除拉黑是软删</b>：{@code status=RELEASED} + {@code releasedAt}/{@code releasedBy} 留痕。</li>
 * </ol>
 */
package ai.neargo.sharehub.platform.notify;
