import { Navigate, Outlet, createBrowserRouter } from "react-router";
import { useAuthStore } from "./stores/auth";
import { AdminLayout } from "./layouts/AdminLayout";
import { LoginPage } from "./pages/LoginPage";
import { DashboardPage } from "./pages/DashboardPage";
import { ContestsPage } from "./pages/contests/ContestsPage";
import { ContestDetailPage } from "./pages/contests/ContestDetailPage";
import { ProblemsPage } from "./pages/problems/ProblemsPage";
import { MonitorEventsPage } from "./pages/monitor/MonitorEventsPage";
import { ForbiddenPage } from "./pages/ForbiddenPage";

/** 管理端路由守卫：未登录跳登录页；考生/无管理角色 → 403 */
function RequireManageRole() {
  const token = useAuthStore((s) => s.token);
  const roles = useAuthStore((s) => s.roles);
  if (!token) return <Navigate to="/login" replace />;
  if (!roles.includes("ADMIN") && !roles.includes("TEACHER")) {
    return <Navigate to="/403" replace />;
  }
  return <Outlet />;
}

export const router = createBrowserRouter([
  { path: "/login", element: <LoginPage /> },
  { path: "/403", element: <ForbiddenPage /> },
  {
    path: "/",
    element: <RequireManageRole />,
    children: [
      {
        element: <AdminLayout />,
        children: [
          { index: true, element: <DashboardPage /> },
          { path: "contests", element: <ContestsPage /> },
          { path: "contests/:contestId", element: <ContestDetailPage /> },
          { path: "problems", element: <ProblemsPage /> },
          { path: "monitor", element: <MonitorEventsPage /> },
        ],
      },
    ],
  },
  { path: "*", element: <Navigate to="/" replace /> },
]);
