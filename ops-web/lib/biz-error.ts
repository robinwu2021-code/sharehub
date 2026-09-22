// 业务规则报错（三语）。
//
// 放在 lib 根而不是 lib/api/mocks/ 下：`lib/mock/db/` 的状态机守卫也要用它，
// 而 db 层不该反过来依赖 api 层。
//
// ## 为什么需要它
//
// mock 层的规则报错此前一律是裸中文 `throw new Error("...")`（整理前 70 处，
// 而用 ApiError 的只有 8 处）。界面切到 EN / AR 之后，页面是英文或阿语、
// 错误提示还是中文 —— 而错误提示恰恰是用户最需要看懂的那一句。
//
// ## 它与真实后端同构
//
// `api/http-client.ts` 发 `Accept-Language`，后端按语言返回本地化 message，前端原样显示。
// mock 在这里做的是同一件事：**在抛出的那一刻**按当前 locale 定稿。
// 于是两种模式下行为一致，且切换语言不会改写已经弹出的那条 toast
// （那句话描述的是过去发生的一次失败，不该随界面语言变形）。
//
// ## 为什么是 `fail(zh, en, ar)` 而不是 i18n key
//
// 这些句子都是一次性的、带上下文的具体说明（「已下发的版本只能改灰度比例」），
// 不是可复用短语。给每条起一个 key，只会得到一堆只被引用一次的 key 和一个必须来回跳的目录。
import { useLocaleStore } from "@/lib/stores/locale";
import { ApiError } from "./api/error";

/**
 * 抛一条业务规则错误（HTTP 400 语义：请求本身合法，是业务规则拒绝）。
 *
 * @param zh 中文说明。**要写清后果**，不要只说「参数不合法」
 * @param en 英文说明。与 zh 同义，不是逐字直译
 * @param ar 阿语说明。缺省回退 en —— 回退到英文总好过回退到中文
 *
 * 返回类型 `never`，所以 `if (bad) fail(...)` 之后 TS 知道后面不可达。
 */
export function fail(zh: string, en: string, ar?: string): never {
  const locale = useLocaleStore.getState().locale;
  throw new ApiError(400, locale === "en" ? en : locale === "ar" ? (ar ?? en) : zh);
}

/** 资源不存在。这一类句式高度一致，单独给一个，省得几十处各写各的。 */
export function notFound(zhEntity: string, enEntity: string, no: string): never {
  const locale = useLocaleStore.getState().locale;
  throw new ApiError(404,
    locale === "zh" ? `${zhEntity}不存在：${no}`
      : locale === "ar" ? `${enEntity} غير موجود: ${no}`
      : `${enEntity} not found: ${no}`);
}
