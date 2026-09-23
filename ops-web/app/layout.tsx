import type { Metadata } from "next";
import { API_MODE } from "@/lib/api-mode";
import { IBM_Plex_Sans, IBM_Plex_Sans_Arabic } from "next/font/google";
import "./globals.css";
import { Providers } from "@/components/providers";
import { AppShell } from "@/components/layout/app-shell";

// 字族：IBM Plex Sans 一族打通三种文字（拉丁 / 阿语 / 中文），全部来自 Google Fonts。
//
// 为什么从 Nunito 换过来：Nunito 是圆润的消费端字体，用在处理金额与设备号的密集
// 台账上偏"软"；IBM Plex 是为技术界面设计的中性无衬线，数字辨识度高（0/O、1/l 分得开），
// 且**同一家族覆盖三种文字**——三语混排时字重、字宽、基线是一致的，
// 拼三个不同家族做不到这一点。
const plex = IBM_Plex_Sans({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  variable: "--font-plex",
  display: "swap",
});
const plexArabic = IBM_Plex_Sans_Arabic({
  subsets: ["arabic"],
  weight: ["400", "500", "600", "700"],
  variable: "--font-plex-ar",
  display: "swap",
});
// ⚠️ 中文走 CDN 而非 next/font：**CJK 家族在 next/font 的字体数据里没有登记**
// （已用 next 自带的 font-data.json 逐个探测确认：IBM Plex Sans / Arabic 都在，
// 但 IBM Plex Sans SC 查无此项）。自托管路径取不到汉字字形，用 latin 子集时
// document.fonts.check(...,'设备') 实测为 false —— 字体加了等于没加，且肉眼看不出来。
// 已知代价：受限网络/内网部署下会静默回退到 PingFang SC / 微软雅黑。

export const metadata: Metadata = {
  title: "powerbank 运营端",
  description: "共享充电宝 SaaS 运营管理后台",
};

// 首帧前应用持久化主题色 + 语言/方向（避免闪烁）。key 对齐 stores/{theme,locale}.ts。
const THEME_INIT = `try{var t=JSON.parse(localStorage.getItem('ops-theme')||'{}');var k=t&&t.state&&t.state.themeKey;if(k)document.documentElement.dataset.theme=k;}catch(e){}
try{var l=JSON.parse(localStorage.getItem('ops-locale')||'{}');var lo=(l&&l.state&&l.state.locale)||'zh';var d=document.documentElement;d.lang=lo==='zh'?'zh-CN':lo;d.dir=lo==='ar'?'rtl':'ltr';}catch(e){}`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh" className={`${plex.variable} ${plexArabic.variable}`} suppressHydrationWarning>
      <head>
        {/* 这份产物连的是 mock 还是真后端。给 scripts/assert-prod-build.mjs 查用 ——
            漏配 USE_MOCK=0 会静默退回 mock，产物里没有别的痕迹能看出来。
            从零依赖的 lib/api-mode 读，不从 lib/api：在根布局 import 后者
            会把整个 mock 拉进服务端构建。 */}
        <meta name="api-mode" content={API_MODE} />
        {/* 中文字体：IBM Plex Sans SC（见上方注释说明为何不走 next/font）*/}
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+SC:wght@400;500;600;700&display=swap"
        />
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT }} />
      </head>
      <body>
        <Providers>
          <AppShell>{children}</AppShell>
        </Providers>
      </body>
    </html>
  );
}
