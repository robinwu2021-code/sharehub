import type { Metadata } from "next";
import { Nunito } from "next/font/google";
import "./globals.css";
import { Providers } from "@/components/providers";
import { AppShell } from "@/components/layout/app-shell";

// 圆润的欧美无衬线字体（Latin），中文回退系统字体（见 globals.css 字体栈）。
const nunito = Nunito({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700", "800"],
  variable: "--font-nunito",
  display: "swap",
});

export const metadata: Metadata = {
  title: "powerbank 运营端",
  description: "共享充电宝 SaaS 运营管理后台",
};

// 首帧前应用持久化主题色 + 语言/方向（避免闪烁）。key 对齐 stores/{theme,locale}.ts。
const THEME_INIT = `try{var t=JSON.parse(localStorage.getItem('ops-theme')||'{}');var k=t&&t.state&&t.state.themeKey;if(k)document.documentElement.dataset.theme=k;}catch(e){}
try{var l=JSON.parse(localStorage.getItem('ops-locale')||'{}');var lo=(l&&l.state&&l.state.locale)||'zh';var d=document.documentElement;d.lang=lo==='zh'?'zh-CN':lo;d.dir=lo==='ar'?'rtl':'ltr';}catch(e){}`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh" className={nunito.variable} suppressHydrationWarning>
      <head>
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
