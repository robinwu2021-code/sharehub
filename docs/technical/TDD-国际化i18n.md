# TDD-国际化 i18n（zh/en/ar，前后端 + 统一 API/mock/消息异常）

**文档状态**：P1 基础设施已实现（2026-07-12）；P2 全量文案外化待做
**关联需求**：[ADR-009 市场区域与合规](./ADR/ADR-009-市场区域与合规.md)（MENA/AED/ar-en RTL/PDPL）· [tech-stack-frontend](./tech-stack-frontend.md)（next-intl/i18next + ar/en RTL）
**创建日期**：2026-07-12

---

## 1. 需求摘要
运营端 + C 端 + 后端支持 **中文 zh / 英文 en / 阿拉伯语 ar（RTL）** 三语。前端统一 API 调用与错误处理、统一 mock、封装消息与异常；后端按既有 `Result/ErrorCode` 规范扩 i18n。

关键验收：
- AC1：切换语言即时生效（含 **ar 整体 RTL**），偏好持久化；`<html lang dir>` 正确。
- AC2：所有 UI 文案、**枚举标签**（状态/类型）、日期/金额格式随 locale 变化；无硬编码中文残留在页面。
- AC3：前端每次请求带 `Accept-Language`；后端按此返回**本地化的 `Result.message`** 与异常消息。
- AC4：mock 与真实后端**同一封套 + 同一错误类型 `ApiError{code,message}`**；mock 消息也走前端 i18n。
- AC5：`tsc`/`next build`/`vitest` 全绿；后端 `mvn test` 绿；实机 zh↔en↔ar 三语 + RTL 验证。

## 2. 当前架构分析
| 层 | 现状 | 关系 |
|---|---|---|
| 前端请求层 `lib/api/http-client.ts` | fetch + AuthHeaders + 拆 `Result<T>{code,message,data}`，失败 `throw new Error(body.message)` | 加 `Accept-Language`；抛 `ApiError` 而非 Error |
| 前端 `lib/api/{contract,mock,http,index}.ts` | 一键切换 mock↔http，契约统一 | mock 错误对齐 `ApiError`；mock 消息本地化 |
| 前端文案 | **全硬编码中文**；枚举标签集中在 `components/status.tsx` + 各页 map（`PB_STATUS`/`WO_TYPE_LABEL` 等） | 外化到 i18n 目录 + `t()` |
| 前端格式化 `lib/utils.ts` | `money()` 固定 en-AE；`fmtTime()` 用 `toLocaleString()` | 随 locale |
| 后端 `common/ApiResponseWrapper` | `ResponseBodyAdvice` 包 `ai.neargo.common.core.Result.ok(data)`（`{code:0,messageःsuccess,data}`） | message 可选本地化 `common.success` |
| 后端 `common/GlobalExceptionHandler` | 异常→`Result.error(code, msg)`；msg **硬编码中英混**（"无权限"/"bad request"/"internal error"） | 换 MessageSource 按 key 解析 |
| 后端异常 `ai.neargo.common.core.ServerException(code,message)` | message 为成品串 | 约定：message 传 **key**，边界解析 |
| 后端 | **无 MessageSource/LocaleResolver** | 新增 |

复用：`Result/ErrorCode/PageResult/ServerException`（neargo commons）契约不改，仅在其上加 i18n 解析；前端 store 复用 theme/nav-prefs 的 zustand+persist+首帧脚本模式。

## 3. 技术方案

### 3.1 方案选型（前端 i18n 载体）
| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A 轻量自建 `lib/i18n`（对齐 ai-kb 的 `useT()`+typed key）| 无路由依赖、契合 `output:export` 静态 SPA、与 ai 生态一致、体积小 | 复数/ICU 需自理（够用） | ✅ 采用 |
| B next-intl | 功能全 | app-router i18n 路由依赖 middleware，与 `output:export` 冲突 | ❌ |
| C i18next | 生态成熟 | 体积大、为静态 SPA 偏重 | ❌（可作 A 的内核，暂不引） |

### 3.2 前端设计
- `lib/i18n/messages/{zh,en,ar}.ts`：按域命名空间的 key（`common.*`/`nav.*`/`status.*`/`device.*`/`order.*`…）。`zh` 为 key 全集基准。
- `lib/i18n/index.ts`：`Locale='zh'|'en'|'ar'`；`t(key, params?)`（`{n}` 插值）；`useT()` hook 订阅 locale store；`DIR:{zh:'ltr',en:'ltr',ar:'rtl'}`。
- `lib/stores/locale.ts`：zustand+persist（key `ops-locale`）`{locale, setLocale}`；`applyLocale()` 写 `<html lang dir>`。`app/layout.tsx` 加首帧脚本防闪（复用 theme 模式）。
- **语言切换器** `components/layout/lang-switcher.tsx`（顶栏，zh/EN/ع 三项），并入 header。
- **枚举标签外化**：`components/status.tsx` + 各页 `PB_STATUS/HEALTH/CMD_TYPE/WO_TYPE_LABEL/PRIO…` 改为读 `t('status.order.IN_USE')` 等；tone（色调）保留在代码。
- **格式化**：`money(amount,currency)` 与 `fmtTime()` 接受/读取当前 locale（`Intl.NumberFormat(localeTag,{currency})`、`Intl.DateTimeFormat`）；AED 不变。
- **RTL**：`dir="rtl"` + 审计布局用**逻辑属性**（`ms-/me-/ps-/pe-/start-/end-`）替换 Rail/面板里少量 `left/right/border-l/border-r/-rotate` 绝对方向；分段/表格/色块天然对称。
- **统一 API 调用/错误**：`lib/api/error.ts` 定义 `ApiError{code:number,message:string}`；`http-client` 失败 `throw new ApiError(body.code, body.message)`（message 已由后端按 Accept-Language 本地化）；请求头加 `"Accept-Language": localeTag()`。`lib/notify.ts` 轻量 toast（成功/失败），mutation 统一 `onError(e)→notify.error(e.message)`。**mock**：`mock.ts` 抛同款 `ApiError`，消息取自前端 i18n（`t('error.xxx')`），成功走 `t('common.success')`；mock 数据仅存**枚举/编码**（专有名词如 Emaar Malls 保留），不内嵌可翻译文案。

### 3.3 后端设计
- `resources/i18n/messages_{zh,en,ar}.properties`（key 与前端 `error.*` 对齐，如 `error.forbidden=无权限 / No permission / ليس لديك صلاحية`）。
- `config/I18nConfig`：`ResourceBundleMessageSource`（basename `i18n/messages`, UTF-8）+ `AcceptHeaderLocaleResolver`（支持 zh/en/ar，默认见 §决策）；`WebMvcConfigurer` 注册。
- `common/Messages`：`msg(key, args...)` 用 `MessageSource + LocaleContextHolder`。
- `GlobalExceptionHandler` 改：`ErrorCode`→key（`ErrorCode.FORBIDDEN`→`error.forbidden`）经 `Messages.msg` 解析；`ServerException` 里 message 约定为 key（无匹配则原样）。`Result.ok` message 可解析 `common.success`。
- 校验/业务错误（如状态机、RBAC）抛 `ServerException(ErrorCode, "error.order.not_returnable")` 之类 key。
- **不改** neargo commons 的 `Result/ErrorCode/ServerException` 类，仅在 powerbank 边界解析。

### 3.4 配置项
| 配置 | 说明 | 位置 |
|---|---|---|
| `LOCALES=['zh','en','ar']` / `DEFAULT_LOCALE` | 支持集与默认 | FE `lib/i18n`；BE `application.yml` |
| `ops-locale` | 前端持久化 key | `lib/stores/locale.ts` |
| `Accept-Language` | 请求头 | `http-client` |

## 4. 测试策略
| 层 | 覆盖 | 工具 |
|---|---|---|
| FE 单元 | `t()` 插值/缺 key 回退；`DIR` 映射；三语 catalog **key 齐平**（无缺漏） | vitest |
| FE 构建 | `tsc` + `next build`（`output:export`） | — |
| FE 实机 | zh↔en↔ar 切换即时 + RTL 版式 + 枚举标签/金额时间随 locale + mock 错误 toast | 浏览器 |
| BE | `Messages.msg` 按 Accept-Language 解析（zh/en/ar）；异常→本地化 Result；缺省回退 | mvn test |

关键场景：① ar 全站 RTL 不错位；② 缺失翻译回退到 zh/key 不崩；③ 真实后端 403 返回对应语言"无权限"；④ mock 与真实错误 toast 一致。

## 5. 风险
- **翻译体量**：约数百条 UI 串 × 3 语言；ar 机翻质量需人工校（见决策）。
- RTL 回归：逐页面审计方向属性，Rail/tooltip/图表坐标轴需重点看。
- `output:export`：i18n 不能用 next-intl 路由，故走 client 端 catalog（已选）。
- 后端 message key 与前端 `error.*` 需**同源对齐**（建 `docs/api` 错误码-文案表）。

## 6. 实现任务（分期）
**P1 基础设施（已实现）**
- [x] FE：`lib/i18n`（catalog zh/en/ar + translate + useI18n/useT + DIR + LOCALE_TAG）、`lib/stores/locale.ts`、layout 首帧脚本、`components/layout/lang-switcher.tsx`、`html lang/dir`
- [x] FE：`lib/api/error.ts`(ApiError) + `http-client` 加 `Accept-Language`/抛 ApiError + 网络错误本地化、`lib/notify.ts`+`Toaster` + providers 全局 mutation onError→toast
- [x] FE：`components/status.tsx`（枚举→色调保留，文案走 t）、`money/fmtTime` 随 locale、`Pagination/DataTable 空态/Rail/SecondaryNav/Header 面包屑/login` 全本地化；`nav-labels.ts` 三语 overlay（nav.ts 中文 SSOT 不动）
- [x] BE：`config/I18nConfig`(ReloadableResourceBundleMessageSource+AcceptHeaderLocaleResolver) + `common/Messages` + 改 `GlobalExceptionHandler`（ErrorCode→key 按 locale 解析，兼容旧成品串）+ `resources/i18n/messages{,_en,_ar}.properties`
- [x] 测试：FE `lib/i18n/i18n.test.ts`（三语齐平/插值/回退，vitest 38 过）+ BE `MessagesTest`（三语解析）+ `tsc`/`next build`(19 路由)/`mvn test-compile`(JDK21) 全绿 + 实机 zh↔en↔ar 切换 + ar 全站 RTL + 货币/日期随 locale
**P2 全量文案外化（另起，建议 workflow 按域并行）**
- [ ] 16 页面 + C 端所有中文串（页面标题/表头/筛选/按钮/抽屉/各页枚举 map）→ key + zh/en/ar；ar 人工校对
- [ ] `lang-switcher`/`nav-prefs toggle` 等少量 aria-label 本地化；图表坐标轴/tooltip RTL 复核

---
确认记录：2026-07-12 用户确认（默认 zh / 先做 P1 基础设施 / ar 自动生成+标记待校）。P1 已实现并实机验证。
