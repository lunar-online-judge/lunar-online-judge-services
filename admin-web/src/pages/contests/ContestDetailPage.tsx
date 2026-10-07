import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button, Chip, Divider, Input, Modal, ModalBody, ModalContent, ModalFooter,
  ModalHeader, Tab, Tabs, Table, TableBody, TableCell, TableColumn, TableHeader,
  TableRow, Textarea,
} from "@heroui/react";
import { ApiError, getData, sendData } from "../../lib/api";
import { formatDateTime } from "../../lib/format";
import { toastError, toastSuccess } from "../../stores/toast";
import type { AnnouncementView, ContestView, StudentView } from "../../types/api";

export function ContestDetailPage() {
  const { contestId = "" } = useParams();
  const queryClient = useQueryClient();
  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["contest", contestId] });
    queryClient.invalidateQueries({ queryKey: ["contest-students", contestId] });
  };

  const contest = useQuery({
    queryKey: ["contest", contestId],
    queryFn: () => getData<ContestView>(`/api/v1/contests/${contestId}`),
  });

  const transition = useMutation({
    mutationFn: (action: string) =>
      sendData(`/api/v1/contests/${contestId}/${action}`, undefined, "POST"),
    onSuccess: () => {
      toastSuccess("操作成功");
      invalidate();
    },
    onError: (e) => toastError(e instanceof ApiError ? `${e.code}: ${e.message}` : "操作失败"),
  });

  if (contest.isLoading) {
    return <p className="text-small text-default-500">加载中…</p>;
  }
  if (contest.isError) {
    return (
      <p className="text-danger">
        加载失败：{contest.error instanceof ApiError ? contest.error.message : "未知错误"}
      </p>
    );
  }
  const c = contest.data;
  if (!c) return null;
  const nextActions: { label: string; action: string }[] = [];
  if (c.status === "DRAFT") nextActions.push({ label: "提交审核", action: "review" });
  if (c.status === "REVIEWING") nextActions.push({ label: "发布", action: "publish" });
  if (c.status === "FINISHED") nextActions.push({ label: "归档", action: "archive" });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <div>
          <Link to="/contests" className="text-small text-primary hover:underline">
            ← 考试列表
          </Link>
          <h1 className="text-2xl font-bold">{c.name}</h1>
          <div className="mt-1 flex items-center gap-2">
            <Chip size="sm" variant="flat">{c.status}</Chip>
            <span className="text-small text-default-500">
              {formatDateTime(c.startAt)} ~ {formatDateTime(c.endAt)}
            </span>
          </div>
        </div>
        <div className="flex gap-2">
          {nextActions.map((a) => (
            <Button
              key={a.action}
              color="primary"
              variant="flat"
              isDisabled={transition.isPending}
              onPress={() => transition.mutate(a.action)}
            >
              {a.label}
            </Button>
          ))}
        </div>
      </div>

      <Tabs aria-label="考试管理">
        <Tab key="students" title="学生名单">
          <StudentsTab contestId={contestId} invalidate={invalidate} />
        </Tab>
        <Tab key="announcements" title="公告">
          <AnnouncementsTab contestId={contestId} invalidate={invalidate} />
        </Tab>
        <Tab key="detail" title="详细信息">
          <div className="flex flex-col gap-2 text-small">
            <p><span className="text-default-500">ID：</span>{c.id}</p>
            <p><span className="text-default-500">描述：</span>{c.description ?? "-"}</p>
            <p><span className="text-default-500">创建人：</span>{c.createdBy}</p>
            <p><span className="text-default-500">创建时间：</span>{formatDateTime(c.createdAt)}</p>
          </div>
        </Tab>
      </Tabs>
    </div>
  );
}

function StudentsTab(props: { contestId: string; invalidate: () => void }) {
  const queryClient = useQueryClient();
  const [addOpen, setAddOpen] = useState(false);
  const [resetTarget, setResetTarget] = useState<StudentView | null>(null);

  const query = useQuery({
    queryKey: ["contest-students", props.contestId],
    queryFn: () =>
      getData<{ items: StudentView[]; total: number }>(
        `/api/v1/contests/${props.contestId}/students?pageSize=100`,
      ),
  });

  const onError = (e: unknown) => toastError(e instanceof ApiError ? `${e.code}: ${e.message}` : "操作失败");

  const remove = useMutation({
    mutationFn: (studentId: string) =>
      sendData(`/api/v1/contests/${props.contestId}/students/${studentId}`, undefined, "DELETE"),
    onSuccess: () => {
      toastSuccess("已移出名单（软删除）");
      queryClient.invalidateQueries({ queryKey: ["contest-students", props.contestId] });
    },
    onError,
  });

  const reset = useMutation({
    mutationFn: ({ studentId, password }: { studentId: string; password: string }) =>
      sendData(`/api/v1/contests/${props.contestId}/students/${studentId}/password-reset`,
        { temporaryPassword: password }, "POST"),
    onSuccess: () => {
      toastSuccess("密码已重置，学生下次登录需修改");
      setResetTarget(null);
      props.invalidate();
    },
    onError,
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex justify-end">
        <Button color="primary" size="sm" onPress={() => setAddOpen(true)}>
          添加学生
        </Button>
      </div>
      <Table aria-label="学生名单">
        <TableHeader>
          <TableColumn>学号</TableColumn>
          <TableColumn>账户名</TableColumn>
          <TableColumn>姓名</TableColumn>
          <TableColumn>状态</TableColumn>
          <TableColumn>加入时间</TableColumn>
          <TableColumn>操作</TableColumn>
        </TableHeader>
        <TableBody items={query.data?.items ?? []} isLoading={query.isLoading} emptyContent="暂无学生">
          {(student) => (
            <TableRow key={student.studentId}>
              <TableCell>{student.studentNo}</TableCell>
              <TableCell>{student.username}</TableCell>
              <TableCell>{student.displayName ?? "-"}</TableCell>
              <TableCell>
                <Chip
                  size="sm"
                  variant="flat"
                  color={student.enrollmentStatus === "ENROLLED" ? "success" : "danger"}
                >
                  {student.enrollmentStatus}
                </Chip>
              </TableCell>
              <TableCell>{formatDateTime(student.enrolledAt)}</TableCell>
              <TableCell>
                {student.enrollmentStatus === "ENROLLED" && (
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="light"
                      onPress={() => setResetTarget(student)}
                    >
                      重置密码
                    </Button>
                    <Button
                      size="sm"
                      color="danger"
                      variant="light"
                      isDisabled={remove.isPending}
                      onPress={() => remove.mutate(student.studentId)}
                    >
                      移出
                    </Button>
                  </div>
                )}
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>

      <AddStudentModal
        contestId={props.contestId}
        isOpen={addOpen}
        onClose={() => setAddOpen(false)}
        invalidate={props.invalidate}
      />
      <ResetPasswordModal
        target={resetTarget}
        onClose={() => setResetTarget(null)}
        onSubmit={(password) => reset.mutate({ studentId: resetTarget!.studentId, password })}
        isSubmitting={reset.isPending}
      />
    </div>
  );
}

function AddStudentModal(props: { contestId: string; isOpen: boolean; onClose: () => void; invalidate: () => void }) {
  const queryClient = useQueryClient();
  const [studentNo, setStudentNo] = useState("");
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [initialPassword, setInitialPassword] = useState("");
  const [error, setError] = useState("");

  const create = useMutation({
    mutationFn: (body: unknown) =>
      sendData(`/api/v1/contests/${props.contestId}/students`, body, "POST"),
    onSuccess: () => {
      toastSuccess("学生已添加（账号经 auth 创建/复用）");
      queryClient.invalidateQueries({ queryKey: ["contest-students", props.contestId] });
      props.onClose();
    },
    onError: (e) => setError(e instanceof ApiError ? `${e.code}: ${e.message}` : "添加失败"),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError("");
    if (initialPassword.length < 8) {
      setError("初始密码至少 8 位");
      return;
    }
    create.mutate({ studentNo, username, displayName, initialPassword });
  };

  return (
    <Modal isOpen={props.isOpen} onClose={props.onClose} size="lg">
      <ModalContent>
        <form onSubmit={submit}>
          <ModalHeader>添加学生（账号由 auth-service 创建或复用）</ModalHeader>
          <ModalBody className="gap-4">
            <Input label="学号" value={studentNo} onValueChange={setStudentNo} isRequired />
            <Input label="登录账户名" value={username} onValueChange={setUsername} isRequired />
            <Input label="姓名" value={displayName} onValueChange={setDisplayName} isRequired />
            <Input
              label="初始密码（至少 8 位）"
              type="text"
              value={initialPassword}
              onValueChange={setInitialPassword}
              isRequired
            />
            {error && <p className="text-small text-danger">{error}</p>}
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={props.onClose}>取消</Button>
            <Button color="primary" type="submit" isLoading={create.isPending}>创建</Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  );
}

function ResetPasswordModal(props: {
  target: StudentView | null;
  onClose: () => void;
  onSubmit: (password: string) => void;
  isSubmitting: boolean;
}) {
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (password.length < 8) {
      setError("密码至少 8 位");
      return;
    }
    props.onSubmit(password);
  };

  return (
    <Modal isOpen={props.target !== null} onClose={props.onClose} size="md">
      <ModalContent>
        <form onSubmit={submit}>
          <ModalHeader>重置密码：{props.target?.displayName ?? props.target?.username}</ModalHeader>
          <ModalBody className="gap-4">
            <Input
              label="新临时密码（至少 8 位）"
              value={password}
              onValueChange={setPassword}
              isRequired
            />
            <p className="text-small text-default-500">
              重置后学生将被要求修改密码；旧密码立即失效。
            </p>
            {error && <p className="text-small text-danger">{error}</p>}
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={props.onClose}>取消</Button>
            <Button color="primary" type="submit" isLoading={props.isSubmitting}>重置</Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  );
}

function AnnouncementsTab(props: { contestId: string; invalidate: () => void }) {
  const queryClient = useQueryClient();
  const [content, setContent] = useState("");
  const [important, setImportant] = useState(false);
  const [requireConfirmation, setRequireConfirmation] = useState(false);

  const query = useQuery({
    queryKey: ["contest-announcements", props.contestId],
    queryFn: () => getData<AnnouncementView[]>(`/api/v1/contests/${props.contestId}/announcements`),
  });

  const publish = useMutation({
    mutationFn: (body: unknown) =>
      sendData(`/api/v1/contests/${props.contestId}/announcements`, body, "POST"),
    onSuccess: () => {
      toastSuccess("公告已发布");
      setContent("");
      queryClient.invalidateQueries({ queryKey: ["contest-announcements", props.contestId] });
    },
    onError: (e) => toastError(e instanceof ApiError ? `${e.code}: ${e.message}` : "发布失败"),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!content.trim()) return;
    publish.mutate({ content, important, requireConfirmation });
  };

  return (
    <div className="flex flex-col gap-4">
      <form onSubmit={submit} className="flex flex-col gap-3 rounded-medium border border-default-200 p-4">
        <Textarea
          label="公告内容"
          value={content}
          onValueChange={setContent}
          isRequired
          maxRows={4}
        />
        <div className="flex gap-6">
          <label className="flex items-center gap-2 text-small">
            <input type="checkbox" checked={important} onChange={(e) => setImportant(e.target.checked)} />
            标记为重要
          </label>
          <label className="flex items-center gap-2 text-small">
            <input
              type="checkbox"
              checked={requireConfirmation}
              onChange={(e) => setRequireConfirmation(e.target.checked)}
            />
            需要学生确认
          </label>
          <Button type="submit" color="primary" size="sm" isLoading={publish.isPending}>
            发布
          </Button>
        </div>
      </form>

      <div className="flex flex-col gap-3">
        {query.data?.map((a) => (
          <div key={a.announcementId} className="rounded-medium border border-default-200 p-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                {a.important && <Chip size="sm" color="warning" variant="flat">重要</Chip>}
                {a.requireConfirmation && <Chip size="sm" color="primary" variant="flat">需确认</Chip>}
              </div>
              <span className="text-tiny text-default-500">
                {a.createdBy.slice(0, 8)}… · {formatDateTime(a.createdAt)}
              </span>
            </div>
            <p className="mt-2 text-small">{a.content}</p>
            {a.requireConfirmation && (
              <>
                <Divider className="my-2" />
                <p className="text-tiny text-default-500">
                  送达：{formatDateTime(a.deliveredAt)} · 确认：{a.confirmedAt ? formatDateTime(a.confirmedAt) : "未确认"}
                </p>
              </>
            )}
          </div>
        ))}
        {query.data?.length === 0 && (
          <p className="text-small text-default-500">暂无公告。</p>
        )}
      </div>
    </div>
  );
}
