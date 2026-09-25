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

## 6. 本文没做的

- **没重写页面交互**：看 [方案-ops-web运营流程页面完善](./方案-ops-web运营流程页面完善.md)。
- **没重写代码落点**：看 [08-前端实现](./TDD-运营核心流程/08-前端实现.md)，
  但注意它假设后端已提交，且批次顺序与本文 §4 有一处不同（设备 vs 告警）。
- **没碰菜单**：08 §七 确认菜单不变，`lib/nav*.test.ts` 应全绿。
  若确需加叶子，注意菜单已改为后端下发（`V81` 灌的 128 行），
  改 `nav.ts` 要连迁移一起动，否则与库里失配。
