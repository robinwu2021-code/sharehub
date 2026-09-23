# lib 分层

判据是**依赖方向**与**认不认 React**：

| 目录 | 是什么 | 认 React？ | 认业务？ |
|---|---|:-:|:-:|
| `api/` | 契约（`contract.ts`）+ mock 实现 + http 实现 + 唯一切换点 `index.ts` | ✗ | ✓ |
| `mock/db/` | mock 的内存数据与状态机。**重开能读回**，非法迁移在这里抛错 | ✗ | ✓ |
| `types/` | 与后端对齐的数据形状 | ✗ | ✓ |
| `rules/` | **纯业务规则函数**，被页面与 mock **共用** | ✗ | ✓ |
| `stores/` | zustand 持久化偏好（语言、主题、导航） | ✓ | ✗ |
| `hooks/` | React hook，认 React 但不认具体页面 | ✓ | 部分 |
| `i18n/` | 三语 catalog + 导航标签 overlay | ✓ | ✗ |
| 根目录 | 基础设施：`utils` / `auth` / `notify` / `permissions` / `nav` / `phase` / `constants` / `biz-error` / `api-mode` | 部分 | 部分 |

## 几条不显然的

- **`rules/` 是页面与 mock 的公共上游。** 规则只写一份，两边同一套判定 ——
  否则会出现「表单允许提交、mock 拒绝」这种自相矛盾。
- **`api-mode.ts` 必须零依赖。** 根布局与 Providers 要判 mock/真后端，
  而在它们里面 import `lib/api` 会把整个 mock 拉进服务端构建。
- **`biz-error.ts` 放在根而不是 `api/mocks/` 下**：`mock/db/` 的状态机守卫也要用它，
  而 db 层不该反过来依赖 api 层。
- **`constants.ts` 的进入标准**：同一个值出现在第二个文件时才搬进来。
  不是所有字面量都要进，那会变成一个谁也读不懂的数字仓库。
