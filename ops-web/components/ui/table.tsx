import * as React from "react";
import { cn } from "@/lib/utils";

export function Table({ className, ...props }: React.HTMLAttributes<HTMLTableElement>) {
  return (
    <div data-surface="table" className="relative w-full overflow-x-auto">
      <table className={cn("w-full caption-bottom txt-body", className)} {...props} />
    </div>
  );
}
export function THead({ className, ...props }: React.HTMLAttributes<HTMLTableSectionElement>) {
  // 表头 = 一条色块（bg-muted），代替下划线
  // 窄屏（< sm，商家用手机看）不折行：表格已在容器里横向滚动，折行只会把「故障维修」竖着排成一字一行
  return <thead data-slot="thead" className={cn("bg-muted max-sm:[&_th]:whitespace-nowrap [&_th]:h-11 [&_th]:px-3.5 [&_th]:text-start [&_th]:align-middle [&_th]:text-xs [&_th]:font-medium [&_th]:text-muted-foreground", className)} {...props} />;
}
export function TBody({ className, ...props }: React.HTMLAttributes<HTMLTableSectionElement>) {
  // 无行线，隔行浅色块（zebra）分隔
  return <tbody data-slot="tbody" className={cn("max-sm:[&_td]:whitespace-nowrap [&_td]:px-3.5 [&_td]:py-3 [&_td]:align-middle [&_tr:nth-child(even)]:bg-muted/45", className)} {...props} />;
}
export function TR({ className, ...props }: React.HTMLAttributes<HTMLTableRowElement>) {
  return <tr className={cn("transition-colors hover:bg-accent/50", className)} {...props} />;
}
export function TH({ className, ...props }: React.ThHTMLAttributes<HTMLTableCellElement>) {
  return <th className={className} {...props} />;
}
export function TD({ className, ...props }: React.TdHTMLAttributes<HTMLTableCellElement>) {
  return <td className={className} {...props} />;
}
