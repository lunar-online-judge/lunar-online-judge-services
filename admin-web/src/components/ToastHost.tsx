import { useToastStore } from "../stores/toast";

const STYLE: Record<string, string> = {
  success: "border-success-300 bg-success-50 text-success-700",
  error: "border-danger-300 bg-danger-50 text-danger-700",
  info: "border-primary-300 bg-primary-50 text-primary-700",
};

/** 轻量 Toast 宿主（HeroUI 2.7 无内置 Toast，自绘固定定位卡片）。 */
export function ToastHost() {
  const toasts = useToastStore((s) => s.toasts);
  const remove = useToastStore((s) => s.remove);

  return (
    <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2">
      {toasts.map((t) => (
        <div
          key={t.id}
          className={`flex items-center justify-between gap-4 rounded-medium border px-4 py-2.5 text-small shadow-medium ${STYLE[t.type]}`}
        >
          <span>{t.message}</span>
          <button className="opacity-60 hover:opacity-100" onClick={() => remove(t.id)}>
            ✕
          </button>
        </div>
      ))}
    </div>
  );
}
