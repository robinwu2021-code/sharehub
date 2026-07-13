"use client";

import * as React from "react";
import { Card } from "./card";
import { Table, THead, TBody, TR, TH, TD } from "./table";
import { Skeleton, EmptyState } from "./misc";
import { useI18n } from "@/lib/i18n";

export interface Column<T> {
  header: string;
  cell: (row: T) => React.ReactNode;
  className?: string;
}

// 通用列表表格：列配置 + 行数据 + 加载/空态。让新列表页保持一致、精简。
export function DataTable<T>({
  columns, rows, loading, rowKey, empty,
}: {
  columns: Column<T>[];
  rows: T[] | undefined;
  loading?: boolean;
  rowKey: (row: T) => string;
  empty?: string;
}) {
  const { t } = useI18n();
  const emptyText = empty ?? t("common.empty");
  return (
    <Card className="overflow-hidden">
      {loading && !rows ? (
        <div className="space-y-2 p-4">{Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="h-10" />)}</div>
      ) : !rows || rows.length === 0 ? (
        <div className="p-4"><EmptyState title={emptyText} /></div>
      ) : (
        <Table>
          <THead>
            <TR>{columns.map((c, i) => <TH key={i} className={c.className}>{c.header}</TH>)}</TR>
          </THead>
          <TBody>
            {rows.map((row) => (
              <TR key={rowKey(row)}>
                {columns.map((c, i) => <TD key={i} className={c.className}>{c.cell(row)}</TD>)}
              </TR>
            ))}
          </TBody>
        </Table>
      )}
    </Card>
  );
}
