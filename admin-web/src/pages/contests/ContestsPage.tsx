import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button, Chip, Input, Modal, ModalBody, ModalContent, ModalFooter,
  ModalHeader, Pagination, Select, SelectItem, Table, TableBody, TableCell,
  TableColumn, TableHeader, TableRow, Textarea,
} from "@heroui/react";
import { ApiError, getPage, sendData } from "../../lib/api";
import { formatDateTime, localInputToIso } from "../../lib/format";
import { toastError, toastSuccess } from "../../stores/toast";
import type { ContestView } from "../../types/api";

const STATUS_COLOR: Record<string, "default" | "primary" | "success" | "warning" | "danger"> = {
  DRAFT: "default",
  REVIEWING: "warning",
  PUBLISHED: "primary",
  RUNNING: "success",
  FINISHED: "default",
  ARCHIVED: "default",
};

const NEXT_ACTION: Record<string, { label: string; action: string; target: string }> = {
  DRAFT: { label: "提交审核", action: "review", target: "REVIEWING" },
  REVIEWING: { label: "发布", action: "publish", target: "PUBLISHED" },
  FINISHED: { label: "归档", action: "archive", target: "ARCHIVED" },
};

export function ContestsPage() {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState("all");
  const [createOpen, setCreateOpen] = useState(false);
  const status = statusFilter === "all" ? undefined : statusFilter;

  const query = useQuery({
    queryKey: ["contests", page, status],
    queryFn: () => getPage<ContestView>("/api/v1/contests", { page, pageSize: 10, status }),
  });

  const transition = useMutation({
    mutationFn: ({ id, action }: { id: string; action: string }) =>
      sendData(`/api/v1/contests/${id}/${action}`, undefined, "POST"),
    onSuccess: () => {
      toastSuccess("操作成功");
      queryClient.invalidateQueries({ queryKey: ["contests"] });
    },
    onError: (e) => toastError(e instanceof ApiError ? `${e.code}: ${e.message}` : "操作失败"),
  });

  const create = useMutation({
    mutationFn: (body: unknown) => sendData("/api/v1/contests", body, "POST"),
    onSuccess: () => {
      toastSuccess("考试已创建");
      setCreateOpen(false);
      queryClient.invalidateQueries({ queryKey: ["contests"] });
    },
    onError: (e) => toastError(e instanceof ApiError ? `${e.code}: ${e.message}` : "创建失败"),
  });

  const totalPages = Math.max(1, Math.ceil((query.data?.total ?? 0) / 10));

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">考试管理</h1>
        <Button color="primary" onPress={() => setCreateOpen(true)}>
          新建考试
        </Button>
      </div>

      <Select
        label="状态过滤"
        selectedKeys={statusFilter ? [statusFilter] : []}
        onChange={(e) => { setStatusFilter(e.target.value); setPage(1); }}
        className="max-w-48"
        size="sm"
      >
        {["all", "DRAFT", "REVIEWING", "PUBLISHED", "RUNNING", "FINISHED", "ARCHIVED"].map((s) => (
          <SelectItem key={s}>{s === "all" ? "全部" : s}</SelectItem>
        ))}
      </Select>

      <Table
        aria-label="考试列表"
        bottomContent={
          <div className="flex justify-center">
            <Pagination
              total={totalPages}
              page={page}
              onChange={setPage}
              showControls
            />
          </div>
        }
      >
        <TableHeader>
          <TableColumn>名称</TableColumn>
          <TableColumn>状态</TableColumn>
          <TableColumn>开始时间</TableColumn>
          <TableColumn>结束时间</TableColumn>
          <TableColumn>操作</TableColumn>
        </TableHeader>
        <TableBody
          items={query.data?.items ?? []}
          isLoading={query.isLoading}
          emptyContent="暂无考试"
        >
          {(contest) => (
            <TableRow key={contest.id}>
              <TableCell>
                <Link
                  to={`/contests/${contest.id}`}
                  className="font-medium text-primary hover:underline"
                >
                  {contest.name}
                </Link>
              </TableCell>
              <TableCell>
                <Chip size="sm" variant="flat" color={STATUS_COLOR[contest.status] ?? "default"}>
                  {contest.status}
                </Chip>
              </TableCell>
              <TableCell>{formatDateTime(contest.startAt)}</TableCell>
              <TableCell>{formatDateTime(contest.endAt)}</TableCell>
              <TableCell>
                <div className="flex gap-2">
                  {NEXT_ACTION[contest.status] && (
                    <Button
                      size="sm"
                      variant="flat"
                      color="primary"
                      isDisabled={transition.isPending}
                      onPress={() =>
                        transition.mutate({ id: contest.id, action: NEXT_ACTION[contest.status].action })
                      }
                    >
                      {NEXT_ACTION[contest.status].label}
                    </Button>
                  )}
                  <Button
                    as={Link}
                    size="sm"
                    variant="light"
                    to={`/contests/${contest.id}`}
                  >
                    管理
                  </Button>
                </div>
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>

      <CreateContestModal
        isOpen={createOpen}
        onClose={() => setCreateOpen(false)}
        onSubmit={(body) => create.mutate(body)}
        isSubmitting={create.isPending}
      />
    </div>
  );
}

function CreateContestModal(props: {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (body: unknown) => void;
  isSubmitting: boolean;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [startAt, setStartAt] = useState("");
  const [endAt, setEndAt] = useState("");
  const [error, setError] = useState("");

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!name || !startAt || !endAt) {
      setError("名称与时间窗口为必填");
      return;
    }
    props.onSubmit({
      name,
      description,
      startAt: localInputToIso(startAt),
      endAt: localInputToIso(endAt),
    });
  };

  return (
    <Modal isOpen={props.isOpen} onClose={props.onClose} size="lg">
      <ModalContent>
        {(onClose) => (
          <form onSubmit={submit}>
            <ModalHeader>新建考试</ModalHeader>
            <ModalBody className="gap-4">
              <Input label="考试名称" value={name} onValueChange={setName} isRequired />
              <Textarea label="描述" value={description} onValueChange={setDescription} />
              <Input
                label="开始时间（UTC）"
                type="datetime-local"
                value={startAt}
                onValueChange={setStartAt}
                isRequired
              />
              <Input
                label="结束时间（UTC）"
                type="datetime-local"
                value={endAt}
                onValueChange={setEndAt}
                isRequired
              />
              {error && <p className="text-small text-danger">{error}</p>}
            </ModalBody>
            <ModalFooter>
              <Button variant="light" onPress={onClose}>取消</Button>
              <Button color="primary" type="submit" isLoading={props.isSubmitting}>
                创建
              </Button>
            </ModalFooter>
          </form>
        )}
      </ModalContent>
    </Modal>
  );
}
