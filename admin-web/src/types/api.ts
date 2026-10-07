/** 统一响应信封（docs/api.md"响应结构"） */
export interface ApiResponse<T> {
  requestId: string;
  data: T;
  error: { code: string; message: string } | null;
}

/** 带分页元数据的列表响应（docs/api.md"分页"） */
export interface PageData<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

// ---------- auth ----------

export interface LoginResponse {
  userId: string;
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  role: string;
  passwordChangeRequired: boolean;
}

export interface StudentView {
  studentId: string;
  studentNo: string;
  username: string;
  displayName: string;
  enrollmentStatus: string;
  addedByTeacherId?: string;
  enrolledAt?: string;
}

// ---------- contest ----------

export interface ContestView {
  id: string;
  name: string;
  description: string | null;
  status: string;
  startAt: string | null;
  endAt: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ContestListItem {
  problemId: string;
  title: string;
  currentVersionId: string;
  createdAt: string;
}

export interface AnnouncementView {
  announcementId: string;
  content: string;
  important: boolean;
  requireConfirmation: boolean;
  createdBy: string;
  createdAt: string;
  deliveredAt: string | null;
  confirmedAt: string | null;
}

// ---------- problem ----------

export interface ProblemVersionView {
  versionId: string;
  problemId: string;
  versionNo: number;
  title: string;
  statement: string;
  sampleInput: string | null;
  sampleOutput: string | null;
  timeLimitMs: number;
  memoryLimitKb: number;
  outputLimitKb: number;
  allowedLanguages: string[];
  status: string;
  createdBy: string;
  createdAt: string;
  publishedAt: string | null;
}

export interface TestcaseView {
  name: string;
  inputContent: string;
  expectedOutput: string;
}

// ---------- monitor ----------

export interface RiskEventView {
  eventId: string;
  contestId: string;
  studentId: string;
  problemId: string | null;
  eventType: string;
  severity: string;
  detail: string | null;
  occurredAt: string;
  reportedAt: string;
  reviewStatus: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
}
