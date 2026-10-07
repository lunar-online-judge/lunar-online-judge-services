import { Link } from "react-router";
import { Card, CardBody, Chip } from "@heroui/react";
import { useQuery } from "@tanstack/react-query";
import { getPage } from "../lib/api";
import { formatDateTime } from "../lib/format";

const CARDS = [
  { to: "/contests", title: "考试管理", desc: "创建、审核、发布与归档考试，管理学生名单与公告", icon: "📝" },
  { to: "/problems", title: "题目管理", desc: "题库、版本发布、测试点与语言白名单", icon: "📚" },
  { to: "/monitor", title: "风险事件", desc: "监考风险事件查询与人工审核", icon: "🛡️" },
];

export function DashboardPage() {
  const contests = useQuery({
    queryKey: ["contests", "dash"],
    queryFn: () => getPage<{ id: string; name: string; status: string; createdAt: string }>(
      "/api/v1/contests", { page: 1, pageSize: 5 }),
  });

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">概览</h1>
        <p className="text-small text-default-500">平台管理入口</p>
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        {CARDS.map((c) => (
          <Link key={c.to} to={c.to} className="transition-transform hover:-translate-y-0.5">
            <Card className="h-full" isPressable>
              <CardBody className="gap-2">
                <p className="text-2xl">{c.icon}</p>
                <p className="font-medium">{c.title}</p>
                <p className="text-small text-default-500">{c.desc}</p>
              </CardBody>
            </Card>
          </Link>
        ))}
      </div>

      <Card>
        <CardBody className="gap-3">
          <div className="flex items-center justify-between">
            <p className="font-medium">最近创建的考试</p>
            <Link to="/contests" className="text-small text-primary hover:underline">
              查看全部 →
            </Link>
          </div>
          {contests.isLoading ? (
            <p className="text-small text-default-500">加载中…</p>
          ) : (
            <div className="flex flex-col gap-2">
              {contests.data?.items.map((c) => (
                <Link
                  key={c.id}
                  to={`/contests/${c.id}`}
                  className="flex items-center justify-between rounded-medium bg-default-100 px-4 py-2.5 text-small hover:bg-default-200"
                >
                  <span>{c.name}</span>
                  <span className="flex items-center gap-3 text-default-500">
                    {formatDateTime(c.createdAt)}
                    <Chip size="sm" variant="flat">{c.status}</Chip>
                  </span>
                </Link>
              ))}
              {contests.data?.items.length === 0 && (
                <p className="text-small text-default-500">暂无考试，去创建第一场。</p>
              )}
            </div>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
