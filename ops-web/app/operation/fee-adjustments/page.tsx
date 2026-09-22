"use client";

// 运营管理 › 预约调价（fee-adjustments）—— F1 占位，见 docs/technical/TDD-运营管理菜单-前端.md
import { PagePending } from "@/components/operation/page-pending";

export default function Page() {
  return (
    <PagePending
      label="预约调价"
      purpose="对标简电「预约调价」：设定在未来某个时间自动改价，可选到期自动恢复原价。"
      reason="本页在 F4 阶段开发。这是新功能，后端的数据表和定时任务都还没有。"
    />
  );
}
