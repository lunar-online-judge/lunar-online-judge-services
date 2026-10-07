import { create } from "zustand";
import { persist } from "zustand/middleware";

interface AuthState {
  token: string;
  userId: string;
  roles: string[];
  setAuth: (token: string, userId: string, roles: string[]) => void;
  clear: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: "",
      userId: "",
      roles: [],
      setAuth: (token, userId, roles) => set({ token, userId, roles }),
      clear: () => set({ token: "", userId: "", roles: [] }),
    }),
    { name: "lunar-admin-auth" },
  ),
);

export function hasManageRole(roles: string[]): boolean {
  return roles.includes("ADMIN") || roles.includes("TEACHER");
}
