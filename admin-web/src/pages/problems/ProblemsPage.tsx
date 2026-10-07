import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button, Chip, Divider, Input, Modal, ModalBody, ModalContent, ModalFooter,
  ModalHeader, Pagination, Table, TableBody, TableCell, TableColumn, TableHeader,
  TableRow, Textarea,
} from "@heroui/react";
import { ApiError, getData, getPage, sendData } from "../../lib/api";
import { formatDateTime } from "../../lib/format";
import { toastError, toastSuccess } from "../../stores/toast";

interface ProblemListItem {
  problemId: string;
  title: string;
  currentVersionId: string;
  createdAt: string;
}

interface VersionView {
  versionId: string;
  problemId: string;
  versionNo: number;
  title: string;
  statement: string;
  timeLimitMs: number;
  memoryLimitKb: number;
  allowedLanguages: string[];
  status: string;
  createdAt: string;
}

interface Testcase {
  name: string;
  inputContent: string;
  expectedOutput: string;
}

export function ProblemsPage() {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [versionsFor, setVersionsFor] = useState<ProblemListItem | null>(null);

  const query = useQuery({
    queryKey: ["problems", page, keyword],
    queryFn: () => getPage<ProblemListItem>("/api/v1/problems", { page, pageSize: 10, keyword: keyword || undefined }),
  });

  const publish = useMutation({
    mutationFn: ({ problemId, versionId }: { problemId: string; versionId: string }) =>
      sendData(`/api/v1/problems/${problemId}/versions/${versionId}/publish`, undefined, "POST"),
    onSuccess: () => {
      toastSuccess("版本已发布");
      queryClient.invalidateQueries({ queryKey: ["problems"] });
    },
    onError: (e) => toastError(e instanceof ApiError ? `${e.code}: ${e.message}` : "发布失败"),
  });

  const totalPages = Math.max(1, Math.ceil((query.data?.total ?? 0) / 10));

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">题目管理</h1>
        <Button color="primary" onPress={() => setCreateOpen(true)}>新建题目</Button>
      </div>

      <Input
        label="按标题搜索"
        value={keyword}
        onValueChange={(v) => { setKeyword(v); setPage(1); }}
        className="max-w-72"
        size="sm"
      />

      <Table
        aria-label="题目列表"
        bottomContent={
          <div className="flex justify-center">
            <Pagination total={totalPages} page={page} onChange={setPage} showControls />
          </div>
        }
      >
        <TableHeader>
          <TableColumn>标题</TableColumn>
          <TableColumn>当前发布版本</TableColumn>
          <TableColumn>创建时间</TableColumn>
          <TableColumn>操作</TableColumn>
        </TableHeader>
        <TableBody items={query.data?.items ?? []} isLoading={query.isLoading} emptyContent="暂无题目">
          {(problem) => (
            <TableRow key={problem.problemId}>
              <TableCell className="font-medium">{problem.title}</TableCell>
              <TableCell>
                {problem.currentVersionId
                  ? <Chip size="sm" color="success" variant="flat">已发布</Chip>
                  : <Chip size="sm" variant="flat">未发布</Chip>}
              </TableCell>
              <TableCell>{formatDateTime(problem.createdAt)}</TableCell>
              <TableCell>
                <Button
                  size="sm"
                  variant="flat"
                  onPress={() => setVersionsFor(problem)}
                >
                  版本管理
                </Button>
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>

      <CreateProblemModal
        isOpen={createOpen}
        onClose={() => setCreateOpen(false)}
      />
      {versionsFor && (
        <VersionsModal
          problem={versionsFor}
          onClose={() => setVersionsFor(null)}
          onPublish={(versionId) => publish.mutate({ problemId: versionsFor.problemId, versionId })}
          isPublishing={publish.isPending}
        />
      )}
    </div>
  );
}

function CreateProblemModal(props: { isOpen: boolean; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [title, setTitle] = useState("");
  const [statement, setStatement] = useState("");
  const [timeLimitMs, setTimeLimitMs] = useState("2000");
  const [memoryLimitKb, setMemoryLimitKb] = useState("262144");
  const [allowedLanguages, setAllowedLanguages] = useState("c,cpp,java,python");
  const [testcases, setTestcases] = useState<Testcase[]>([
    { name: "01", inputContent: "", expectedOutput: "" },
  ]);
  const [error, setError] = useState("");

  const create = useMutation({
    mutationFn: (body: unknown) => sendData("/api/v1/problems", body, "POST"),
    onSuccess: () => {
      toastSuccess("题目已创建（v1 草稿，含测试点）");
      queryClient.invalidateQueries({ queryKey: ["problems"] });
      props.onClose();
    },
    onError: (e) => setError(e instanceof ApiError ? `${e.code}: ${e.message}` : "创建失败"),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!title || !statement) {
      setError("标题与题面必填");
      return;
    }
    if (testcases.some((t) => !t.name || !t.inputContent || !t.expectedOutput)) {
      setError("测试点的名称/输入/期望输出均必填");
      return;
    }
    create.mutate({
      title, statement,
      timeLimitMs: Number(timeLimitMs), memoryLimitKb: Number(memoryLimitKb),
      allowedLanguages, testcases,
    });
  };

  const updateTc = (index: number, patch: Partial<Testcase>) => {
    setTestcases((tcs) => tcs.map((tc, i) => (i === index ? { ...tc, ...patch } : tc)));
  };

  return (
    <Modal isOpen={props.isOpen} onClose={props.onClose} size="2xl" scrollBehavior="inside">
      <ModalContent>
        <form onSubmit={submit}>
          <ModalHeader>新建题目</ModalHeader>
          <ModalBody className="gap-4">
            <Input label="标题" value={title} onValueChange={setTitle} isRequired />
            <Textarea label="题面" value={statement} onValueChange={setStatement} isRequired minRows={4} />
            <div className="grid grid-cols-3 gap-3">
              <Input label="CPU 限制(ms)" type="number" value={timeLimitMs} onValueChange={setTimeLimitMs} />
              <Input label="内存限制(KB)" type="number" value={memoryLimitKb} onValueChange={setMemoryLimitKb} />
              <Input label="语言白名单" value={allowedLanguages} onValueChange={setAllowedLanguages} />
            </div>
            <Divider />
            <div className="flex flex-col gap-3">
              <div className="flex items-center justify-between">
                <p className="font-medium">测试点</p>
                <Button
                  size="sm"
                  variant="flat"
                  onPress={() => setTestcases((tcs) => [
                    ...tcs,
                    { name: String(tcs.length + 1).padStart(2, "0"), inputContent: "", expectedOutput: "" },
                  ])}
                >
                  添加测试点
                </Button>
              </div>
              {testcases.map((tc, i) => (
                <div key={i} className="grid grid-cols-[80px_1fr_1fr_40px] items-center gap-2">
                  <Input label="名称" value={tc.name} onValueChange={(v) => updateTc(i, { name: v })} size="sm" />
                  <Input label="输入" value={tc.inputContent} onValueChange={(v) => updateTc(i, { inputContent: v })} size="sm" />
                  <Input label="期望输出" value={tc.expectedOutput} onValueChange={(v) => updateTc(i, { expectedOutput: v })} size="sm" />
                  <Button
                    size="sm"
                    variant="light"
                    color="danger"
                    isDisabled={testcases.length <= 1}
                    onPress={() => setTestcases((tcs) => tcs.filter((_, idx) => idx !== i))}
                  >
                    删
                  </Button>
                </div>
              ))}
            </div>
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

function VersionsModal(props: {
  problem: ProblemListItem;
  onClose: () => void;
  onPublish: (versionId: string) => void;
  isPublishing: boolean;
}) {
  const [testcasesFor, setTestcasesFor] = useState<string | null>(null);
  const query = useQuery({
    queryKey: ["problem-versions", props.problem.problemId],
    queryFn: () => getData<VersionView[]>(`/api/v1/problems/${props.problem.problemId}/versions`),
  });

  const testcases = useQuery({
    queryKey: ["problem-testcases", testcasesFor],
    enabled: testcasesFor !== null,
    queryFn: () =>
      getData<VersionTestcases[]>(`/api/v1/problems/${props.problem.problemId}/versions/${testcasesFor}/testcases`),
  });

  return (
    <Modal isOpen onClose={props.onClose} size="2xl" scrollBehavior="inside">
      <ModalContent>
        <ModalHeader>版本管理：{props.problem.title}</ModalHeader>
        <ModalBody className="gap-3 pb-6">
          <Table aria-label="版本列表" removeWrapper>
            <TableHeader>
              <TableColumn>版本</TableColumn>
              <TableColumn>状态</TableColumn>
              <TableColumn>语言</TableColumn>
              <TableColumn>创建时间</TableColumn>
              <TableColumn>操作</TableColumn>
            </TableHeader>
            <TableBody items={query.data ?? []} isLoading={query.isLoading}>
              {(v) => (
                <TableRow key={v.versionId}>
                  <TableCell>v{v.versionNo}</TableCell>
                  <TableCell>
                    <Chip
                      size="sm"
                      variant="flat"
                      color={v.status === "PUBLISHED" ? "success" : "default"}
                    >
                      {v.status}
                    </Chip>
                  </TableCell>
                  <TableCell className="text-tiny">{v.allowedLanguages.join(", ")}</TableCell>
                  <TableCell className="text-tiny">{formatDateTime(v.createdAt)}</TableCell>
                  <TableCell>
                    <div className="flex gap-2">
                      <Button size="sm" variant="light" onPress={() => setTestcasesFor(v.versionId)}>
                        测试点
                      </Button>
                      {v.status === "DRAFT" && (
                        <Button
                          size="sm"
                          color="primary"
                          variant="flat"
                          isDisabled={props.isPublishing}
                          onPress={() => props.onPublish(v.versionId)}
                        >
                          发布
                        </Button>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>

          {testcasesFor && (
            <div className="rounded-medium border border-default-200 p-4">
              <p className="mb-2 font-medium">测试点（仅管理与判题可见）</p>
              {testcases.isLoading ? (
                <p className="text-small text-default-500">加载中…</p>
              ) : (
                <div className="flex flex-col gap-2">
                  {testcases.data?.map((t) => (
                    <div key={t.name} className="text-tiny">
                      <p className="font-medium">{t.name}</p>
                      <p className="text-default-500">输入：{t.inputContent || "(空)"}</p>
                      <p className="text-default-500">期望：{t.expectedOutput || "(空)"}</p>
                    </div>
                  ))}
                  {testcases.data?.length === 0 && (
                    <p className="text-small text-default-500">无测试点。</p>
                  )}
                </div>
              )}
              <Button
                size="sm"
                variant="light"
                className="mt-2"
                onPress={() => setTestcasesFor(null)}
              >
                收起
              </Button>
            </div>
          )}
        </ModalBody>
      </ModalContent>
    </Modal>
  );
}

interface VersionTestcases {
  name: string;
  inputContent: string;
  expectedOutput: string;
}
