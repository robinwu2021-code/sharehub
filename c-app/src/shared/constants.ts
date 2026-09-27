// 业务常量（零硬编码，P4）。存储键 / 默认语言 / 币种 / 轮询与超时阈值。
export const STORAGE = {
  token: "pb_token",
  user: "pb_user",
  lang: "pb_lang",
  skin: "pb_skin",
  mode: "pb_mode",
} as const;

export const DEFAULT_LANG = "en";

// 语言目录（中/英/阿），native 名用于选择器展示；RTL 仅 ar
export const LANGS = [
  { id: "zh", label: "中文", rtl: false },
  { id: "en", label: "English", rtl: false },
  { id: "ar", label: "العربية", rtl: true },
] as const;
export const CURRENCY = "AED";

// 借还状态机轮询（等弹出/使用中计费）
export const POLL_INTERVAL_MS = 1500;
// 弹出超时阈值（超过触发解冻/关单兜底，C端功能清单 C-RT-04）
export const DISPENSE_TIMEOUT_MS = 12000;

// 进行中订单详情的刷新间隔。比弹出轮询慢一个量级 —— 那边在等一个几秒内的回执，
// 这边等的是「他去还宝」这种以分钟计的事，1.5s 一次只是在烧电量和流量。
export const ORDER_REFRESH_MS = 15000;

// mock 模拟网络延迟
export const MOCK_DELAY_MS = 220;
