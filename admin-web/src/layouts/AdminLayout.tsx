import { NavLink, Outlet, useNavigate } from "react-router";
import { Avatar, Divider, Tooltip } from "@heroui/react";
import { useAuthStore } from "../stores/auth";

const NAV_ITEMS = [
  { to: "/", label: "概览", icon: "📊", end: true },
  { to: "/contests", label: "考试管理", icon: "📝", end: false },
  { to: "/problems", label: "题目管理", icon: "📚", end: false },
  { to: "/monitor", label: "风险事件", icon: "🛡️", end: false },
];

export function AdminLayout() {
  const navigate = useNavigate();
  const userId = useAuthStore((s) => s.userId);
  const roles = useAuthStore((s) => s.roles);
  const clear = useAuthStore((s) => s.clear);

  const logout = () => {
    clear();
    navigate("/login", { replace: true });
  };

  return (
    <div className="flex min-h-screen">
      <aside className="flex w-56 flex-col border-r border-default-200 bg-content1">
        <div className="px-5 py-6">
          <p className="text-lg font-bold">Lunar OJ</p>
          <p className="text-sm text-default-500">管理后台</p>
        </div>
        <Divider />
        <nav className="flex-1 px-3 py-4">
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                `mb-1 flex items-center gap-3 rounded-medium px-3 py-2.5 text-sm transition-colors ${
                  isActive
                    ? "bg-primary-100 text-primary-600 font-medium"
                    : "text-default-600 hover:bg-default-100"
                }`
              }
            >
              <span>{item.icon}</span>
              {item.label}
            </NavLink>
          ))}
        </nav>
        <Divider />
        <div className="flex items-center justify-between px-4 py-4">
          <div className="flex items-center gap-2">
            <Avatar size="sm" name={userId.slice(0, 2).toUpperCase()} />
            <div className="text-xs">
              <p className="font-medium">{userId.slice(0, 8)}…</p>
              <p className="text-default-500">{roles.join(", ")}</p>
            </div>
          </div>
          <Tooltip content="退出登录">
            <button
              onClick={logout}
              className="text-small text-danger hover:underline"
            >
              退出
            </button>
          </Tooltip>
        </div>
      </aside>
      <main className="flex-1 bg-background p-6">
        <Outlet />
      </main>
    </div>
  );
}
