# 待办：ops-web 接入运营核心流程 · 实测对齐与工单

> 2026-09-25 建立。**给「在另一个会话里执行」用的工单**，自包含。
>
> **本文不重复已有设计**：交互与版式看
> [方案-ops-web运营流程页面完善](./方案-ops-web运营流程页面完善.md)（已定稿），
> 代码落点看 [TDD-运营核心流程/08-前端实现](./TDD-运营核心流程/08-前端实现.md)。
> 本文只做那两份没做的事：**对着代码量了一遍现状**，给出依赖排序的开工顺序，
> 以及三处「文档没提到、但不先处理就会白做」的坑。

---

## 0. 先决条件：后端这批还没提交

量的时候 `git status` 显示后端 P1–P4 **全部在工作树里未提交**（大量 `M`，
还有一个被删的 `SiteLifecycleService.java`），迁移 V94–V103 同样未提交。

⇒ **下面所有端点与枚举现在只存在于某个会话的工作树中。**
对着未提交的形状接前端，对方一改就得重对。

**开工前确认**：`git log --oneline -- backend/sharehub-svc-platform/.../loc/` 能看到那批提交，
且 `docs/api/contract.json` 已刷新。

⚠️ **2026-09-25 复核：「先做不依赖后端的那一档」基本不成立。** 逐个查过：
58 个待接端点**全部**落在未提交的控制器里（`ContractController` · `SiteController` ·
`FileController` · `DeviceOpsController` · `AlarmTodoController` · `DeviceSignalController`
都是 `??`；`AlarmController` · `WoExtController` · `LocExtController` · `OpsController` 是 `M`），
三个新枚举同样是未跟踪文件，连 §3 里 `SiteServiceImpl:95` 的 `IN_TRANSIT` 引用
也来自未提交的新文件（所以那不是存量缺陷，是他们在途工作的一部分）。
已提交控制器里的 C 类只剩 3 条详情端点（`settlements/{}` · `agent/accounts/{}` ·
`agent/commissions/{}`），且页面未必需要它们。

⇒ **除了下面 §2 末记的那件（卡口可见性），这条链上没有可以提前开工的部分。**

---

## 1. 实测：后端做好了 58 个端点，运营端一个都没调

来源：`backend/known-api-align-gaps.txt` 的 C 类（后端有、运营端未调用），
由 `npm run check:drift` 里的 `api-align.py --strict` 生成。46 个落在 `/api/ops`。

| 域 | 条数 | 端点 |
|---|:-:|---|
| **设备** | 12 | `GET devices/{}/go-live-gate` · `GET/POST devices/{}/trial-rents` · `GET/POST devices/{}/protections` · `POST devices/protections/{}/release` · `POST devices/{}/go-live\|mark-fault\|repair\|retire\|undeploy` · `GET device-signals` |
| **告警** | 10 | `GET alarm-todos` · `GET alarm-todos/count` · `POST alarm-todos/{}/done` · `GET alarms/summary` · `GET alarms/records/{}` · `GET alarms/records/{}/disposition-preview` · `POST alarms/records/{}/dispose` · `GET alarms/codes/stats` · `GET/POST alarms/codes/{}/routes` |
| **合同** | 9 | `POST contracts/{}/submit\|withdraw\|audit\|sign\|terminate\|renew` · `GET contracts/{}` · `GET contracts/{}/logs` · `GET contracts/summary` |
| **站点** | 8 | `GET sites/{}` · `GET sites/summary` · `GET sites/{}/opening-checklist` · `GET sites/{}/close-gate` · `GET sites/{}/status-logs` · `POST sites/{}/withdraw\|close` · `GET site-lifecycles/funnel` |
| **文件** | 4 | `POST /api/platform/files` · `GET /api/platform/files/{}` · `GET .../{}/url` · `GET .../raw/{}` |
| **工单** | 3 | `GET work-orders/{}` · `GET work-orders/summary` · `GET work-orders/assignee-candidates` |
| 其他 | 12 | 各域详情端点（`leads/{}`、`venue-onboardings/{}`、`sla-rules/{}`、`inspection-plans/{}`、`settlements/{}`…）与 C 端发票 |

> 台账是棘轮（**只准变短**）：接好一条就从文件里删一条，不删也红。

---

## 2. 实测：状态词表三处硬对不上 —— 这是最该先做的一档

两份现有文档都按「应该改成什么」写，没量过「现在是什么」。实测差距：

| 域 | 后端（工作树） | ops-web 现状 | 后果 |
|---|---|---|---|
| **合同** | 6 态 `DRAFT/PENDING/SIGNED/ACTIVE/EXPIRED/TERMINATED`（`loc/ContractStatus.java`） | `lib/types/location.ts:89` **`status: "ACTIVE" \| "EXPIRED"`** | 4 个态前端不认。走审批的合同在列表里显示原始英文值，按状态筛也筛不出 |
| **站点** | `PREPARING/ACTIVE/PAUSED/WITHDRAWING/CLOSED`（`loc/SiteStatus.java`） | `location.ts:268` `SITE_STAGES = PROSPECTING/SIGNED/LIVE/ACTIVE/CHURNED/CLOSED` | **两套完全不同的词表**，只有 `ACTIVE`/`CLOSED` 偶然重合。不是补几个值，是整套换掉：`SITE_STAGES` · `nextSiteStages` · `canSiteStageTransition` 三个一起删 |
| **告警** | `AlarmDomain` 等一组新枚举 | `lib/types/alarm.ts` **一个都没有** | 业务告警的域 / 成因 / 影响面在界面上无处落脚 |

### ⚠️ 为什么卡口没提前抓到这三处（2026-09-25 补）

`StatusVocabularyAcrossEndsTest` 的判据是**两端同名即比对** ——
扫后端 `public enum X`、扫 `ops-web/lib/types` 下 `export type X = "A" | "B"`，
名字相同才成对。它的类注释写着「自动发现，不靠人登记」，
**但那只对具名类型成立**。

`Contract.status` 写成 `status: "ACTIVE" | "EXPIRED";` **内联在 interface 里**，
它配不上对 —— 一个字都比不了，于是差 4 个态而卡口全绿。

实测规模：后端 90 个枚举，能比对上的只有 **34 对**，而 `lib/types` 下有
**75 处内联联合**。与 `StoredValueInVocabularyTest` 的列名单盲区是同一个形状：
**卡口声称自动覆盖，实际覆盖取决于一个没写在标题里的前提。**

已做（commit `9be8bc7`）：抽出 4 个取值集有辨识度、后端同名枚举已提交的
（`CsSenderType` · `DeviceCodeType` · `CodeBatchStatus` · `ReconTaskStatus`），
并加 `lib/types/inline-status-union.test.ts` 棘轮（基线 71，只准降）。
**刻意不动其余 71 处** —— `ACTIVE|DISABLED` 这类通用两值集在后端会撞上
一堆无关枚举，硬起名字只会造出假配对，让卡口强制一段本来无关的耦合。

**`Contract.status` / `Site.status` 这两处抽成具名要等后端提交**：
那两个新枚举已在工作树里，现在抽会立刻让共享工作树变红。
后端提交后，抽名与对齐取值在同一个提交里做完。

### 为什么这一档必须单独一个提交

它会牵动两条跨端卡口 —— `StatusVocabularyAcrossEndsTest`（比取值）与
`StateMachineEdgeAcrossEndsTest`（比迁移边）。和业务页面混在一个提交里改，
红了分不清是词表引起的还是页面引起的。

### 一个时间点上的运气

2026-09-25 之前，`StatusBadge` 是 `map[value].tone` —— **映射不到就 TypeError，
而它在表格 cell 里，于是整页白屏**。当天已改成降级显示原始值（commit `b27d5aa`）。
所以上面三处现在的表现是「显示错」，不是「打不开」。
接入时别把这个兜底当成"没问题"：**原始英文值出现在界面上，本身就是词表没对齐的信号**。

---

## 3. 实测：机柜状态四方不一致（两份文档都没提到）

| 处 | 声明 |
|---|---|
| `dev_cabinet.status` 列注释 | **5 态**，含 `IN_TRANSIT` |
| `CabinetStatus.java` 枚举 | **4 态**，`IN_STOCK/DEPLOYED/FAULT/RETIRED`，没有 `IN_TRANSIT` |
| ops-web `lib/types/device.ts:17` | 4 态，与枚举一致 |
| `SiteServiceImpl:95` | 按 **5 态**算「机柜在场」：`Set.of("IN_STOCK","IN_TRANSIT","DEPLOYED","FAULT")` |

⇒ 门禁逻辑在数一个**枚举产生不出来**的状态。要么补枚举（连同前端与列注释三处一起），
要么删那个字符串。08-前端实现 §一 写「`CabinetStatus` 追加 `IN_TRANSIT`」——
那就得**后端枚举先加**，否则前端加了也永远收不到这个值。

> 另：测试库里有 1 台机柜 `status = ''`（空串，`CAB1001`），生产 0 台。
> 顺带暴露 `StoredValueInVocabularyTest` 的一个盲区 —— 它过滤 `<> ''`，**看不见空值**。

---

## 4. 开工顺序（按依赖排，不按域排）

| # | 事项 | 依赖 | 为什么排这里 |
|:-:|---|---|---|
| **0** | 等后端提交（§0） | — | 对着未提交的形状接前端会白做 |
| **1** | **文件上传**（4 端点 + `lib/types/file.ts`） | 0 | 合同附件、工单照片、告警凭证都依赖它。不先做，后面三批都要留桩 |
| **2** | **词表三件套**（§2） | 0 | 纯类型层、无 UI，但它决定后面所有页面显示对不对。**单独一个提交** |
| **3** | **合同审批链**（9 端点） | 1 · 2 | 流程起点，也是四项裁决的第一条 |
| **4** | **站点五态 + 开业/关闭门禁**（8 端点） | 2 · 3 | 合同签了才谈得上开业 |
| **5** | **设备上线门禁 / 试借还 / 保护**（12 端点） | 4 | 最大一块，门禁依赖站点态 |
| **6** | **业务告警 + 待办**（10 端点） | 5 | 依赖设备信号 |
| **7** | **工单增强**（3 端点） | 6 | 依赖告警 |
| **8** | 机柜 `IN_TRANSIT` 四方对齐（§3） | 后端先定 | 独立小项 |

对应 08-前端实现 §五 的批次：本文 1–2 ≈ B1，3–4 ≈ B2，6–7 ≈ B3，5 ≈ B4。
**顺序以本文为准**（08 的 B4 排在告警后面，但设备门禁是告警的输入）。

---

## 4.1 进度（2026-09-25 晚）

| 批次 | 状态 | 提交 |
|---|---|---|
| 1 词表（合同六态 / 站点五态 / 生命周期降只读漏斗） | ✅ | `346c631` |
| 2 文件上传 | ✅ | `7d90be5` |
| 3 合同审批链（12 端点 + 页面） | ✅ | `dad38bf` · `9c3b35b` |
| 4 站点五态与门禁（7 端点 + 页面） | ✅ | `c0e40d0` |
| 5a 设备门禁 / 试借还 / 保护 —— **数据层** | ✅ | `6e0b8a6` |
| 6a 业务告警 + 待办 —— **数据层** | ✅ | `2ada0ca` |
| 7a 工单增强 —— **数据层** | ✅ | 见 git log |
| 5b/6b/7b 三个页面 UI | ✅ | `b46e18c` |
| 8 机柜 `IN_TRANSIT` 四方对齐（§3） | ✅ | `b46e18c` |
| 9 线索 CRM / 站点踏勘 / 代理清退 / 结算调整 / 对账单（本文未列，后端 A–G 的其余部分） | ✅ | `b46e18c` |

> **2026-09-25 夜 · 接手完成**。撞车已解除（另一会话交接并停止），三个临时类型文件
> （`device-ops.ts` · `alarm-biz.ts` · `workorder-ext.ts`）已按其文件头的约定**并回**
> `device.ts` / `alarm.ts` / `workorder.ts` 并删除 —— 临时落点没有变成新约定。
> 台账也补完了：`known-api-align-gaps.txt` 196 → 30 行，`check:drift` 七项全过。
>
> 顺带修掉本文 §4.1 之外的两件事：
> 1. **合同留痕事件名两端对不上** —— 详情时间线的 `EVENT_LABEL` 用的是一套自拟的名字
>    （`AUDIT_APPROVE` / `TERMINATION_REQUEST`…），后端 `ContractServiceImpl` 写的是另一套
>    （`APPROVE` / `TERM_REQUEST`…17 个）。对不上的表现不是报错，是**时间线上显示一串英文常量**。
>    已建 `ContractLogEvent` 具名类型，前端标签、mock、用例一起对到后端。
> 2. **`/agents` 整页白屏** —— `profiles` 未在 `nav.ts` 登记（菜单里那条不带 `?tab=` 的裸叶
>    被默认当成了 `TAB_KEYS[0]`）。既有缺陷，页面代码 / 菜单 / 类型 / 全部既有测试都是绿的。
>    补 `lib/nav-page-tabs.test.ts` 扫所有页面逐个解析 tab，并要求
>    `useNavTabs` 与 `usePageTab` 两处 `defaultKey` 一致。
>
> **本阶段验不到的**：库存调拨与资产差异是 L2，被分期屏蔽挡住（页面显示「功能尚未开放」），
> 代码已接上、mock 有用例，等 L2 解锁再在浏览器里验。

### 接手时发现：这三处的后端给了运营端不存在的路由

设备上线门禁、站点开业清单、代理清退门禁的 `fixHref` 都是
`/devices/CAB1000?tab=qc` · `/sites/ST300?tab=survey` · `/agents/AG031?tab=assign` 这种形状，
**运营端没有这些路由**，点「去处理」一律 404。三处前端各自做了一层改写兜住，
但真源应当在后端 —— 已记在下面的「需要后端处理」里。

## 5. 动手前必须知道的（ops-web 特有）

这些在 `CLAUDE.md` 与设计规范里都有，但散着，接入时最容易撞上的是这几条：

### 5.1 契约要改三处，还要改锚数组

`lib/api/contracts/<域>.ts`（接口）· `lib/api/mocks/<域>.ts`（mock 实现）·
`lib/api/https/<域>.ts`（真实现），**三处各一份**，再把方法名加进
`lib/api/contract.test.ts` 的锚数组。漏掉任何一处：漏 mock → 离线开发时该功能不存在；
漏 https → 切后端当场 404；漏锚数组 → 这条契约没有任何卡口守着。

**契约禁止 `delete*`** —— 全站软删除语义，用 `archive*` / `unarchive*`。

### 5.2 mock 必须真改 `db`

「保存」只改界面 state 的话，离线开发时看着成功、刷新就没了。
状态机要在 mock 层强制、**非法迁移抛错** —— mock 放行而后端拒绝，
离线调一路顺、切后端当场 500（本仓库已踩过四次）。

### 5.3 棘轮基线只降不升（注意 CLAUDE.md 里的数字是旧的）

`lib/design-tokens.test.ts` 的**当前**基线：

| 项 | 当前 | CLAUDE.md 写的 |
|---|:-:|:-:|
| 手写「仅可查看」 | **≤ 1** | 17（已过期） |
| 内联 `Badge tone={` | **≤ 2** | 58（已过期） |
| 裸筛选 `<Select>` | **≤ 0** | — |
| 泛化空态 `empty="暂无数据"` | **≤ 1** | — |
| 圆角豁免清单 | ≤ 2 | — |

新页面一律 `<StatusBadge>` + `<ReadOnlyNotice>` + `<FilterSelect>` + 解释性空态。
`StatusBadge` 现在映射不到会降级显示原始值（不再白屏），
但**降级是兜底不是许可** —— 见 §2 末。

### 5.4 权限码：未登记的码 `can()` 一律拒，超管也不放行

新码要同时进 `lib/perm-map.ts` 的 `UI_PERM_MAP` 与 `lib/permissions.ts` 的角色默认权限，
且后端端点必须已登记在 `docs/requirements/功能权限清单.md`。
**只在前端登记而后端没强制**，会触发 `perm-ssot-align` 的「声明未强制」卡口。
提现「登记打款」按钮就栽在这上面（登记了码但超管点不动）。

合同三码 `location:contract:submit/audit/terminate` 随后端端点同一提交登记。
**新端点不要挂** `location:site:update` / `workorder:alarm:update` / `workorder:wo:update`
—— 代码在用但不在权限真源表（存量漂移，已在 `known-perm-ssot-gaps` 台账）。

### 5.5 收尾一定要跑的两条

```bash
npx vitest run          # 棘轮 + 跨端词表 + 菜单快照
npm run check:drift     # 7 个检查器：api-align / perm-parity / feature-align / i18n-parity …
```

`check:drift` 里的 `api-align.py --strict` 会拦住「新增缺口」，
所以**接好一条就从 `known-api-align-gaps.txt` 删一条**，不删也红。

### 5.6 改 `globals.css` 的 token 后必须清 `.next` 重启

Turbopack 会缓存类名候选与部分 CSS，出现过三次「源码改了但 served CSS 是旧的」，
**肉眼完全看不出来**。最可靠的确认：`curl` 拉 served CSS 逐字检查 token 在不在。

### 5.7 别在 dev server 运行时跑 `npm run build`

两者共用 `.next`，会污染 dev 的 chunk。

---

## 5.8 接入时实测出来的后端缺口（前端已兜住，真源要回后端）

下面每条都是**对着真后端点出来的**，不是读代码推的。前端做了兜底所以界面可用，
但兜底逻辑放在前端就意味着：换一个客户端（C 端、代理端、第三方）会再踩一次。

| # | 缺口 | 现在前端怎么兜的 | 后端该怎么改 |
|:-:|---|---|---|
| 1 | **门禁 `fixHref` 指向不存在的路由**（设备 / 站点 / 代理清退三处） | 各自一层改写成运营端真实路由 | 直接按运营端路由出链接，前端改写随即失效 |
| 2 ✅ | ~~人工派单不写 `assignee_type`~~ | — | **2026-09-26 已修**（`49bcbd7`）：按受理人反查代理 / 员工写对三列；驳回三列一起清；顺带修了「受理人超 36 字符 → 派单 500」 |
| 3 | **零明细的调拨单可以发货** | 前端拦住 | 服务端补校验（实测把空单发出去了） |
| 4 | **划拨候选端点返回裸数组、参数名不一致、不支持排除** | https 层适配 | 统一成 `PageResult` 与既有分页参数 |
| 5 | **`/api/agent/assignments` 是空实现** | 详情改从可划拨资产池按归属数 | 要么实现，要么删端点（留着比没有更误导） |
| 6 | **找不到代理当前的在途清退单** | 详情里加了「按单号查进度」输入框 | 加 `GET /api/agent/agents/{agentNo}/exit`，或在代理档案带出 `exitNo` |
| 7 | **清退中的代理仍可被改回启用** | 前端禁用按钮 | 服务端拒绝（前端禁用只是提示，闸在服务端） |
| 8 | **调整项列表缺 `period`、不支持按类型 / 来源筛选** | 当前页内补筛并提示总数偏大 | 补字段与参数：保底补差按账期生成，看不出月份等于看不懂 |
| 9 ✅ | ~~接单 / 提交处理 / 验收的权限码两端不一致~~ | — | **2026-09-26 已修**（`b1f9bf2`）：端点改判真源表的 `:handle`/`:close`/`inspection:update`，V116 从权限目录删掉三个废弃码。⚠️ 原判断「其他角色会 403」不准确：OPS 持 `workorder:*` 通配，真正的毛病是**这三个码谁都没持有，目录里却列着**——勾了等于没勾 |
| 10 ✅ | ~~抢单写进时间线的派单策略恒为 `MANUAL`~~ | — | **2026-09-26 已修**（`49bcbd7`）：主表写 GRAB、时间轴写 MANUAL，同一件事两处两个答案 |
| 11 | **机柜 / 充电宝出参没有质检状态与所在仓** | 详情页用最近一条质检记录代替 | 出参补上；另需要仓库列表端点（现在只能手填仓库编号） |
| 12 | **告警列表没有「已处置未关闭 / 今日自愈」的筛选参数** | 两张卡做成不可点 | `AlarmQuery` 补参数 |
| 13 ✅ | ~~提现列表没有 `payeeNo` 参数~~ | — | **2026-09-26 已修**：三层补上 `payeeNo` 精确筛，前端撤掉「按名字取一页再页内过滤」的兜底。⚠️ 原描述不全：`WithdrawalQ` 类型与 mock 侧**本来就支持** `payeeNo`，前端也一直在传 —— 真后端静默忽略它，于是又加了一层按名字取。这类「参数传了没报错也没生效」最难发现 |
| 14 | **站点可借率 / 可还率 / 受影响订单数没有接口** | 摘要条只用 summary 的四个数 | 按方案 §8.1 补 |

> **2026-09-26 进度**：14 条里 3 条已修（#2 #9 #10，见上表）。
> 顺带补上了充电宝疑似丢失的前端入口（`a0ee00d`）——那是后端 V113 上线后一直没有界面的两个端点。
>
> 修 #9 时顺手顶出 `perm-ssot-align` 的**第四个解析盲区**：真源表里那行
> 「命名对齐记录：原 X 从未被任何实现引用，现统一为 Y」被展开器读成了声明。
> 端点改判新码后，强制侧没了、误读的声明侧还在，两条旧码立刻变成假缺口。
> 改脚本前后逐行 diff 比对过，只少掉那两条误报。

## 6. 本文没做的

- **没重写页面交互**：看 [方案-ops-web运营流程页面完善](./方案-ops-web运营流程页面完善.md)。
- **没重写代码落点**：看 [08-前端实现](./TDD-运营核心流程/08-前端实现.md)，
  但注意它假设后端已提交，且批次顺序与本文 §4 有一处不同（设备 vs 告警）。
- **没碰菜单**：08 §七 确认菜单不变，`lib/nav*.test.ts` 应全绿。
  若确需加叶子，注意菜单已改为后端下发（`V81` 灌的 128 行），
  改 `nav.ts` 要连迁移一起动，否则与库里失配。
