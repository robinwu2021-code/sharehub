import type { Metadata } from "next";
import { Nunito, Tajawal } from "next/font/google";
import "./globals.css";
import { Providers } from "@/components/providers";
import { AppShell } from "@/components/layout/app-shell";

// 三个字族按字形自动回退，与 C 端 c-app/index.html 加载的完全同一组
// （拉丁 Nunito / 阿语 Tajawal / 中文 Noto Sans SC）。
// 走 next/font 构建期自托管而非 C 端那样的 Google Fonts CDN：本环境已证实
// 会拦外部请求（地图瓦片就挂在这上面），CDN 字体在受限网络/内网部署下会静默
// 回退成系统字，两端看着就不是一个产品了。
const nunito = Nunito({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700", "800"],
  variable: "--font-nunito",
  display: "swap",
});
// 阿语此前完全没有字体 —— 这不是风格差异是缺陷：ops-web 支持 ar + RTL，
// 却只能靠系统兜底，Windows 上尤其难看。
const tajawal = Tajawal({
  subsets: ["arabic"],
  weight: ["400", "500", "700", "800"],
  variable: "--font-tajawal",
  display: "swap",
});
// ⚠️ 中文的 Noto Sans SC 走 CDN 而不是 next/font，**这是被迫的不是偷懒**：
// 该字族在 next/font 的字体数据里只登记了 cyrillic/latin/latin-ext/vietnamese，
// 没有 chinese-simplified，自托管路径根本取不到汉字字形（用 latin 子集时
// document.fonts.check(...,'设备') 实测为 false，字体加了等于没加）。
// 故与 C 端 c-app/index.html 采用同一条 CDN link（见下方 <head>），两端字形一致。
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
    <html lang="zh" className={`${nunito.variable} ${tajawal.variable}`} suppressHydrationWarning>
      <head>
        {/* 中文字体：与 C 端 c-app/index.html 同一条 CDN link（见上方注释说明为何不走 next/font）*/}
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;500;700&display=swap"
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
