import { useNavigate } from "react-router";
import { Button, Card, CardBody } from "@heroui/react";

export function ForbiddenPage() {
  const navigate = useNavigate();
  return (
    <div className="flex min-h-screen items-center justify-center">
      <Card className="max-w-md">
        <CardBody className="items-center gap-4 py-10">
          <p className="text-4xl">🚫</p>
          <p className="text-lg font-medium">403 · 无管理后台权限</p>
          <p className="text-small text-default-500">
            当前账号不是老师或管理员角色。
          </p>
          <Button variant="flat" onPress={() => navigate("/login")}>
            返回登录
          </Button>
        </CardBody>
      </Card>
    </div>
  );
}
