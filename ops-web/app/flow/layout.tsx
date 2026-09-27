"use client";

// 流程与工单模块的闸：取数目前是浏览器内 mock（lib/flow/client.ts），
// 真实后端模式的产物里若照常渲染，运营看到的是**演示数据**，而且和真的一模一样。
// 菜单叶已按同一常量灰显（nav.ts / menu-source.ts）；这里再挡住深链直达。
// 对接 opskit-svc 后把 backend-ready.ts 的 FLOW_BACKEND_READY 改成 true，这道闸自动撤掉。
import { flowReady } from "@/lib/backend-ready";
import { Notice } from "@/components/ui/notice";
import { useFlowT } from "@/lib/flow/i18n";

export default function FlowLayout({ children }: { children: React.ReactNode }) {
  const { tc } = useFlowT("common");
  if (!flowReady()) return <Notice>{tc("notReady")}</Notice>;
  return <>{children}</>;
}
