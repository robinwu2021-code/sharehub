# 接入指引 · 状态机库（21/07 X2）

把业务里手写的状态判断（`if (status == PENDING && action == APPROVE) …`）换成**声明一次、处处一致**的状态机。
纯 Java、零依赖（只依赖 `opskit-commons`，后者只依赖 JDK），进程内调用，不需要起 OpsKit 服务。
同一份定义以后可以原样登记到 OpsKit 服务（`PUT /processes/{code}`），升级为带审批 / SLA / 待办的流程。

> 本文第 2、3、5 节的代码已原样在空白工程里编译并跑绿（见文末记录）。

## 1. 依赖（2 分钟）

构件还没发布到公共仓库，先在 OpsKit 目录装进本机仓库：

```bash
opskit/scripts/verify.sh -q -pl process/process-api -am install -DskipTests
```

```xml
<dependency>
  <groupId>ai.neargo.opskit</groupId>
  <artifactId>process-api</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

JDK 21。它不带 Spring、不带 JDBC，放进领域层也不会破坏分层。

## 2. 声明（5 分钟）

状态与事件都用枚举 —— 两种类型不可混用，传错了编译不过。

```java
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.statemachine.TypedStateMachine;

enum WithdrawalStatus { PENDING_AUDIT, APPROVED, REJECTED, PAID, CANCELLED }
enum WithdrawalEvent { APPROVE, REJECT, PAY, CANCEL }

static final TypedStateMachine<WithdrawalStatus, WithdrawalEvent> FLOW =
    TypedStateMachine.builder("withdrawal", WithdrawalStatus.class, WithdrawalEvent.class)
        .status(WithdrawalStatus.PENDING_AUDIT, StatusCategory.IN_PROGRESS)
        .status(WithdrawalStatus.APPROVED, StatusCategory.IN_PROGRESS)
        .status(WithdrawalStatus.PAID, StatusCategory.DONE)
        .status(WithdrawalStatus.REJECTED, StatusCategory.CANCELLED)
        .status(WithdrawalStatus.CANCELLED, StatusCategory.CANCELLED)
        .initial(WithdrawalStatus.PENDING_AUDIT)
        .transition(WithdrawalEvent.APPROVE, WithdrawalStatus.PENDING_AUDIT, WithdrawalStatus.APPROVED)
        .transition(WithdrawalEvent.REJECT, WithdrawalStatus.PENDING_AUDIT, WithdrawalStatus.REJECTED)
        .transition(WithdrawalEvent.PAY, WithdrawalStatus.APPROVED, WithdrawalStatus.PAID)
        .transition(WithdrawalEvent.CANCEL, WithdrawalStatus.PENDING_AUDIT, WithdrawalStatus.CANCELLED)
        .build();
```

- **终态由类别推导**（DONE / CANCELLED），不能手写 —— 所以不会出现「标了终态却还有出去的迁移」。
- 图不合法在 `build()` 时就抛 `GraphDefinitionException`（到不了的状态、终态有出边、重复代号……），放在 `static final` 里，
  应用启动即暴露，不会等到线上某个分支才炸。

## 3. 使用（5 分钟）

```java
WithdrawalStatus next = FLOW.next(w.getStatus(), WithdrawalEvent.APPROVE); // 非法迁移抛 IllegalTransitionException
w.setStatus(next);

FLOW.canFire(status, WithdrawalEvent.PAY);   // 界面上这个按钮亮不亮
FLOW.events(status);                         // 当前可走的全部事件（按钮列表）
FLOW.isTerminal(status);                     // 是否已结束
```

**把异常翻成你的错误码**（一处即可）：`IllegalTransitionException` 带 `from()` 与 `transition()`，错误键
`opskit.process.illegal_transition`；多数系统映射为 409（状态已变）或 422（不允许的操作）。

**条件更新防并发**：状态机只回答「能不能、去哪」，落库仍要 `UPDATE … SET status = :next WHERE id = :id AND status = :from`，
影响 0 行 = 被别人抢先改了。

## 4. 兼容旧调用点（按需）

- 旧代码只有起止状态（`assertTransit(from, to)`）：`FLOW.eventFor(from, to)` 反查事件；同一对起止有多个事件时抛
  `AmbiguousEventException`，逼你在调用点说清楚是哪一个。
- 旧代码 / 前端只认「起 → 止集合」：`FLOW.projection()`。
- 前后端一致卡口：`FLOW.untyped().definition()` 导出定义，由卡口比对前端的状态表。

## 5. 测试（5 分钟）

一条「全表」用例胜过十条零散用例：枚举所有（状态, 事件）组合，断言允许集合恰好等于你期望的那几条。

```java
@Test
void onlyTheseTransitionsAreAllowed() {
    Set<String> allowed = new TreeSet<>();
    for (WithdrawalStatus s : WithdrawalStatus.values())
        for (WithdrawalEvent e : WithdrawalEvent.values())
            if (FLOW.canFire(s, e)) allowed.add(s + "-" + e + "->" + FLOW.next(s, e));
    assertEquals(Set.of("APPROVED-PAY->PAID", "PENDING_AUDIT-APPROVE->APPROVED",
            "PENDING_AUDIT-CANCEL->CANCELLED", "PENDING_AUDIT-REJECT->REJECTED"), allowed);
}
```

改图时这条会红，逼着你确认每一条新增 / 删除的迁移是故意的。

## 6. 以后要审批 / SLA / 待办？

同一份 `ProcessDef` 登记到 OpsKit 服务即可（`PUT /processes/{code}`，请求体见 `deploy/opskit/definitions.example/`），
再按 [宿主接入](host-integration.md) 用应用凭证发起实例、收 webhook 回写业务行。业务状态的真源仍在你的系统。

---

### 实测记录（2026-09-28）

在空目录新建 Maven 工程（只含上面的依赖与 JUnit 5）→ 原样贴第 2、3、5 节代码（外加两句 `next` / `eventFor` 的断言）→ `mvn test`：
**2 条用例全绿，机器耗时 3 秒**（依赖已在本机仓库；首次还要下 JUnit，另计网络时间）。

这证明的是「照文档做能成」，**不是**「新人 30 分钟内能完成」—— 后者要找一位没接触过本库的开发者实测计时，尚未做。
按步骤估算：读文档 10 分钟 + 声明自己的状态 10 分钟 + 全表用例 5 分钟。
