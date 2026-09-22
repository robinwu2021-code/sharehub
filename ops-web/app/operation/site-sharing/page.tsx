"use client";

// 运营管理 › 站点分成（site-sharing）—— F1 占位，见 docs/technical/TDD-运营管理菜单-前端.md
import { PagePending } from "@/components/operation/page-pending";

export default function Page() {
  return (
    <PagePending
      label="站点分成"
      purpose="对标简电「站场分成」：按站点查看收入分给谁、各占多少、平台留存多少。"
      reason="分成比例目前存在两处（分润规则与进场合同），要先确定以哪一处为准（运营管理清单 D2），之后再开发本页。"
      legacy={{ href: "/finance?tab=rules", label: "财务管理 › 分润规则" }}
    />
  );
}
