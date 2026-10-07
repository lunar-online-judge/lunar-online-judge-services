import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { Button, Card, CardBody, CardHeader, Input } from "@heroui/react";
import { sendData } from "../lib/api";
import type { LoginResponse } from "../types/api";
import { useAuthStore } from "../stores/auth";
import { decodeJwtRoles } from "../lib/format";
import { toastError } from "../stores/toast";

export function LoginPage() {
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError("");
    try {
      const data = await sendData<LoginResponse>("/api/v1/auth/login", {
        username,
        password,
      });
      const roles = decodeJwtRoles(data.accessToken);
      if (!roles.includes("ADMIN") && !roles.includes("TEACHER")) {
        setError("该账号无管理后台权限");
        return;
      }
      setAuth(data.accessToken, data.userId, roles);
      navigate("/", { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : "登录失败");
      toastError("登录失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-primary-50 to-default-100">
      <Card className="w-full max-w-md">
        <CardHeader className="flex flex-col gap-1 pb-0">
          <p className="text-xl font-bold">Lunar OJ 管理后台</p>
          <p className="text-small text-default-500">使用管理端账号登录</p>
        </CardHeader>
        <CardBody>
          <form onSubmit={onSubmit} className="flex flex-col gap-4">
            <Input
              label="用户名"
              value={username}
              onValueChange={setUsername}
              isRequired
              autoFocus
            />
            <Input
              label="密码"
              type="password"
              value={password}
              onValueChange={setPassword}
              isRequired
            />
            {error && <p className="text-small text-danger">{error}</p>}
            <Button type="submit" color="primary" isLoading={loading}>
              登录
            </Button>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
