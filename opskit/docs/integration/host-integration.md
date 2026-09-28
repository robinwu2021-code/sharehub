# 接入指引 · 宿主系统接 OpsKit 服务（21/07 X1）

「谁批 / 谁办 / 超时没」交给 OpsKit，**业务记录与业务状态留在你的系统**。你的系统做四件事：送审、收回写、回复确认、对账。
`opskit-connector`（纯 JDK HttpClient + Jackson 3，不依赖 Spring）把这四件事的易错处做掉了；下面每段都对应一个类。
对真服务的端到端用例：`opskit-svc` 的 `BootstrapIntrospectionTest#connectorDrivesConfirmationGatesAndReconciliation`。

## 0. 开通（部署方做一次）

`bootstrap.yml` 里声明应用与订阅（凭证由你生成、两边 env 同值，见 `deploy/opskit/bootstrap.yml.example`）：

```yaml
apps:
  myhost: { credential: ${OPSKIT_APP_CREDENTIAL}, signing-secret: ${OPSKIT_APP_SIGNING_SECRET} }
webhooks:
  - { app: myhost, url: http://127.0.0.1:8082/internal/opskit/webhook, events: [instance.*, approval.completed, ticket.*] }
```

人要能被指派 / 当审批人，先同步目录：`POST /directory/sync`（只认应用凭证，见 21/06 §三）。

## 1. 送审 —— `OpsKitConnector.start`

```java
OpsKitConnector opskit = OpsKitConnector.builder()
        .baseUrl("http://127.0.0.1:8090/api/opskit/v1").workspace("ext:MAIN")
        .credential(System.getenv("OPSKIT_APP_CREDENTIAL")).build();

Started s = opskit.start(new StartInstance("withdrawal_audit",
                Subject.of("withdrawal", w.getNo(), "提现 AED 1,200 · " + agentNo),
                Map.of("amount", w.getAmount()), Set.of("agent:" + agentNo),
                OnBehalfOf.of(submitterId, submitterName)),          // 审批单显示真实提交人；自审禁止据此判
        "opskit:withdrawal:" + w.getNo() + ":" + round);             // 幂等键：同一轮重投拿同一个实例
```

- **在业务事务里别直接调它**：业务写入与一条「待送审」发件箱记录同事务，提交后再投递（失败退避重试、耗尽转人工）。
  这样 OpsKit 挂了也不会丢数据、不会半截状态。
- 重投安全：请求体按固定顺序序列化（OpsKit 判「同内容」按字节），`Started.replayed()=true` 表示这是重放。
- 出错抛 `OpsKitCallException`：按 `errorKey()` 分支（文案见 `opskit/docs/error-keys.json`）；`retryable()` 告诉你原样重试有没有意义。

## 2. 收回写 —— `WebhookReceiver`

```java
WebhookReceiver receiver = new WebhookReceiver(System.getenv("OPSKIT_APP_SIGNING_SECRET"), ledger, Clock.systemUTC())
        .on("approval.completed", e -> withdrawals.onAudit(e.subjectRef(), e.data().path("result").asString(),
                e.data().path("reason").asString(null), e.externalRef("decidedBy")))
        .on("instance.awaiting_confirmation", e -> confirmations.check(e));

// 你的端点（任何框架）：把签名头与原始请求体字符串交进去，按返回码应答
Response r = receiver.handle(request.getHeader("X-OpsKit-Signature"), rawBody);
```

| 情形 | 接收器的应答 | 为什么 |
|---|---|---|
| 签名不对 / 时间戳超出 ±5 分钟 | 401，不碰业务 | 防伪造、防重放 |
| 重复事件（台账已记） | 200，不再处理 | OpsKit 至少投递一次 |
| 没有处理器的类型 | 200 | 确认收到，否则会被重投到耗尽 |
| 处理器抛异常 | 撤销台账 → 500 | OpsKit 退避重投，下次还能处理 |

- **台账**（`EventLedger`）生产要用数据库唯一键实现，最好与业务推进同一事务。
- **顺序**：同一实例 / 工单的事件可能乱序到达 —— 存最后处理的 `e.seq()`，收到更小的直接丢弃。`approval.completed` 按 `approvalNo:roundNo` 去重即可。
- **业务已不在等待态**（有人在你的系统里先处理了）：回 200 并记 WARN，不要报错 —— 报错只会触发无意义的重投。
- 验签必须用**原始字节**：不能先反序列化再序列化。
- 上线前跑一遍 **TCK**：`WebhookTck.run(URI.create(端点), 签名密钥).forEach(System.out::println)` —— 六条全 ✅ 再切流量。

## 3. 回复确认 —— `replyConfirmation`

流程里的「外部确认门」（如「激活成功才生效」）会发 `instance.awaiting_confirmation`，令牌只在事件里：

```java
Replied r = opskit.replyConfirmation(e.data().path("token").asString(), activationOk, activationOk ? null : "设备离线");
```

连接器用的是 `POST /confirmations`（令牌在请求体里）；自己写 HTTP 调用时也请用它 —— 令牌放路径里会被反代的访问日志原样记下。
回复本身幂等：重复回复返回首次结论（`first=false`），不会再触发迁移 —— 超时后放心重试。令牌过期 → `opskit.confirmation.expired`。

## 4. 对账 —— `Reconciler`

每天一次（业务时区凌晨），把「等待审批超过 24 小时」的业务行逐条对一遍：

```java
Outcome o = new Reconciler(opskit).check("withdrawal", w.getNo(), "withdrawal_audit");
switch (o.state()) {
    case NOT_FOUND -> markSubmitFailed(w);                 // 送审没成功：进重送
    case APPROVED, REJECTED, WITHDRAWN -> applyOutcome(w, o); // 与 webhook 处理走同一段代码，并记 WARN（说明 webhook 丢过）
    case IN_PROGRESS -> { }                              // 还在审
    case CLOSED -> alertHuman(w, o);                     // 实例结束但不是审批结论（被作废等）
}
```

## 5. 界面：决定只在一处做

审批的通过 / 驳回一律在 OpsKit 的审批收件箱（运营台 `/flow/approvals`）；你的业务页只显示进度与「去审批」链接，
**执行类按钮**（放款、发货）留在业务页。两处都能点「通过」，就一定会出现两边状态打架。
