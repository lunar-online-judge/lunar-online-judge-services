import { useMemo, useState, type FormEvent } from "react";
import {
  useMutation, useQuery, useQueryClient,
} from "@tanstack/react-query";
import {
  Button, Chip, Input, Modal, ModalBody, ModalContent, ModalFooter,
  ModalHeader, Select, SelectItem, Table, TableBody, TableCell, TableColumn,
  TableHeader, TableRow, Textarea,
} from "@heroui/react";
import {
  createColumnHelper, flexRender, getCoreRowModel, useReactTable,
} from "@tanstack/react-table";
import { getPage, sendData } from "../../lib/api";
import { formatDateTime } from "../../lib/format";
import { toastError, toastSuccess } from "../../stores/toast";
import type { RiskEventView } from "../../types/api";

const SEVERITY_COLOR: Record<string, "success" | "warning" | "danger"> = {
  LOW: "success",
  MEDIUM: "warning",
  HIGH: "danger",
};

const columnHelper = createColumnHelper<RiskEventView>();

export function MonitorEventsPage() {
  const queryClient = useQueryClient();
  const [contestId, setContestId] = useState("");
  const [severity, setSeverity] = useState("all");
  const [reviewStatus, setReviewStatus] = useState("all");
  const [reviewTarget, setReviewTarget] = useState<RiskEventView | null>(null);

  const query = useQuery({
    queryKey: ["monitor-events", contestId, severity, reviewStatus],
    queryFn: () =>
      getPage<RiskEventView>("/api/v1/monitor/events", {
        contestId: contestId || undefined,
        severity: severity === "all" ? undefined : severity,
        reviewStatus: reviewStatus === "all" ? undefined : reviewStatus,
      }),
    refetchInterval: 15000, // 监考视图 15s 轮询
  });

  const review = useMutation({
    mutationFn: ({ eventId, action, note }: { eventId: string; action: string; note?: string }) =>
      sendData(`/api/v1/monitor/events/${eventId}/review`, { action, note }, "POST"),
    onSuccess: () => {
      toastSuccess("审核完成");
      setReviewTarget(null);
      queryClient.invalidateQueries({ queryKey: ["monitor-events"] });
    },
    onError: (e) => toastError(e instanceof Error ? e.message : "审核失败"),
  });

  const columns = useMemo(
    () => [
      columnHelper.accessor("eventType", { header: "事件类型" }),
      columnHelper.accessor("severity", {
        header: "等级",
        cell: (info) => (
          <Chip size="sm" variant="flat" color={SEVERITY_COLOR[info.getValue()] ?? "default"}>
            {info.getValue()}
          </Chip>
        ),
      }),
      columnHelper.accessor("studentId", {
        header: "学生",
        cell: (info) => <span className="text-tiny">{info.getValue().slice(0, 8)}…</span>,
      }),
      columnHelper.accessor("detail", {
        header: "详情",
        cell: (info) => <span className="text-tiny">{info.getValue() ?? "-"}</span>,
      }),
      columnHelper.accessor("occurredAt", {
        header: "发生时间",
        cell: (info) => <span className="text-tiny">{formatDateTime(info.getValue())}</span>,
      }),
      columnHelper.accessor("reviewStatus", {
        header: "审核状态",
        cell: (info) => (
          <Chip
            size="sm"
            variant="flat"
            color={info.getValue() === "OPEN" ? "warning" : "default"}
          >
            {info.getValue()}
          </Chip>
        ),
      }),
      columnHelper.display({
        id: "actions",
        header: "操作",
        cell: (info) => {
          const row = info.row.original;
          if (row.reviewStatus === "CONFIRMED" || row.reviewStatus === "DISMISSED") {
            return <span className="text-tiny text-default-400">已终审</span>;
          }
          return (
            <div className="flex gap-1">
              <Button size="sm" variant="light" onPress={() => setReviewTarget(row)}>
                审核
              </Button>
              {row.reviewStatus === "OPEN" && (
                <Button
                  size="sm"
                  color="primary"
                  variant="light"
                  onPress={() => review.mutate({ eventId: row.eventId, action: "ACKNOWLEDGE" })}
                >
                  已知悉
                </Button>
              )}
            </div>
          );
        },
      }),
    ],
    [review],
  );

  const table = useReactTable({
    data: query.data?.items ?? [],
    columns,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">风险事件</h1>
        <p className="text-small text-default-500">每 15 秒自动刷新</p>
      </div>

      <div className="flex gap-3">
        <Input
          label="竞赛 ID"
          value={contestId}
          onValueChange={setContestId}
          className="max-w-72"
          size="sm"
          placeholder="留空查看全部"
        />
        <Select
          label="等级"
          selectedKeys={severity ? [severity] : []}
          onChange={(e) => setSeverity(e.target.value)}
          className="max-w-40"
          size="sm"
        >
          {["all", "HIGH", "MEDIUM", "LOW"].map((s) => (
            <SelectItem key={s}>{s === "all" ? "全部" : s}</SelectItem>
          ))}
        </Select>
        <Select
          label="审核状态"
          selectedKeys={reviewStatus ? [reviewStatus] : []}
          onChange={(e) => setReviewStatus(e.target.value)}
          className="max-w-40"
          size="sm"
        >
          {["all", "OPEN", "ACKNOWLEDGED", "CONFIRMED", "DISMISSED"].map((s) => (
            <SelectItem key={s}>{s === "all" ? "全部" : s}</SelectItem>
          ))}
        </Select>
      </div>

      <Table aria-label="风险事件列表" removeWrapper>
        <TableHeader columns={table.getAllLeafColumns()}>
          {(column) => <TableColumn key={column.id}>{column.columnDef.header as string}</TableColumn>}
        </TableHeader>
        <TableBody
          items={table.getRowModel().rows}
          isLoading={query.isLoading}
          emptyContent="暂无风险事件"
        >
          {(row) => (
            <TableRow key={row.id}>
              {row.getVisibleCells().map((cell) => (
                <TableCell key={cell.id}>
                  {flexRender(cell.column.columnDef.cell, cell.getContext())}
                </TableCell>
              ))}
            </TableRow>
          )}
        </TableBody>
      </Table>

      <ReviewModal
        target={reviewTarget}
        onClose={() => setReviewTarget(null)}
        onSubmit={(action, note) => review.mutate({ eventId: reviewTarget!.eventId, action, note })}
        isSubmitting={review.isPending}
      />
    </div>
  );
}

function ReviewModal(props: {
  target: RiskEventView | null;
  onClose: () => void;
  onSubmit: (action: string, note?: string) => void;
  isSubmitting: boolean;
}) {
  const [action, setAction] = useState("ACKNOWLEDGE");
  const [note, setNote] = useState("");

  const submit = (e: FormEvent) => {
    e.preventDefault();
    props.onSubmit(action, note || undefined);
  };

  return (
    <Modal isOpen={props.target !== null} onClose={() => props.onClose()} size="md">
      <ModalContent>
        <form onSubmit={submit}>
          <ModalHeader>审核风险事件</ModalHeader>
          <ModalBody className="gap-4">
            {props.target && (
              <div className="text-small">
                <p><span className="text-default-500">类型：</span>{props.target.eventType}</p>
                <p><span className="text-default-500">等级：</span>{props.target.severity}</p>
                <p><span className="text-default-500">详情：</span>{props.target.detail ?? "-"}</p>
              </div>
            )}
            <Select
              label="审核动作"
              selectedKeys={action ? [action] : []}
              onChange={(e) => setAction(e.target.value)}
            >
              {["ACKNOWLEDGE", "CONFIRM", "DISMISS"].map((a) => (
                <SelectItem key={a}>{a}</SelectItem>
              ))}
            </Select>
            <Textarea label="备注（可选）" value={note} onValueChange={setNote} maxRows={3} />
            <p className="text-tiny text-default-500">
              流转规则：OPEN → ACKNOWLEDGED → CONFIRMED / DISMISSED；已终审事件不可再变更。
            </p>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={props.onClose}>取消</Button>
            <Button color="primary" type="submit" isLoading={props.isSubmitting}>提交</Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  );
}
