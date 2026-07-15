export type Severity = 'PASS' | 'WARNING' | 'BLOCKED' | 'FAILED'

export interface TerminalStatus {
  version: string
  backend: string
  loopbackOnly: boolean
  controlHome: string
  launcherCount: number
  instanceCount: number
  selectedInstanceId?: string
  runtime?: string
  mod?: string
  companions?: number
  mode?: string
  at: string
}

export interface Launcher {
  id: string
  type: 'PCL2' | 'HMCL'
  version: string
  executable: string
  dataDirectory: string
  confidence: string
  evidence: Record<string, string>
}

export interface Instance {
  id: string
  launcherId: string
  name: string
  minecraftVersion: string
  loader: string
  loaderVersion: string
  gameDir: string
  javaRequired: number
  javaConfigured: string
  confidence: string
  isolation: string
  compatible: boolean
  installed: boolean
  mode: 'FULL' | 'LOCAL_ONLY'
}

export interface DoctorCheck {
  severity: Severity
  code: string
  summary: string
  evidence: Record<string, string>
  repairs: string[]
  repairable: boolean
}

export interface DoctorResult {
  instanceId: string
  state: Severity
  checks: DoctorCheck[]
}

export interface RuntimeStatus {
  instanceId: string
  configured: boolean
  port?: number
  healthPort?: number
  pid?: number
  pidAlive?: boolean
  healthy?: boolean
  identityMatches?: boolean
  runtimeVersion?: string
  protocolVersion?: string
  sessions?: number
  detail?: string
}

export interface SessionStatus {
  instanceId: string
  connected: boolean
  mode: string
  runtimeHealthy: boolean
  sessions: number
  companions: number
}

export interface Companion {
  id: string
  displayName: string
  online: boolean
  lastSeenAt: number
  status?: Record<string, unknown>
  leaseActive: boolean
  controlEpoch?: number
  leaseMode?: string
  leaseExpiresAt?: number
}

export interface Task {
  taskId: string
  companionId: string
  type: string
  state: string
  revision: number
  behaviorId?: string
  behaviorRevision: number
  controlEpoch: number
  reconciliationRequired: boolean
  createdAt: number
  updatedAt: number
}

export interface BehaviorEvent {
  sequence: number
  taskId: string
  revision: number
  eventType: string
  payload?: Record<string, unknown>
  createdAt: number
}

export interface CompanionSnapshot {
  instanceId: string
  mode: string
  companions: Companion[]
  tasks: Task[]
  events: BehaviorEvent[]
  conversations: ConversationEvent[]
  waitingQuestions: WaitingQuestion[]
  error?: string
}

export interface ConversationOption { id: string; label: string; description: string }
export interface ConversationEvent {
  eventId: string; companionId: string; planId?: string; questionId?: string
  direction: string; kind: string; content: string; gameDelivered: boolean; createdAt: number
  payload?: Record<string, unknown>
}
export interface WaitingQuestion {
  questionId: string; planId: string; companionId: string; prompt: string; reason: string
  options: ConversationOption[]; freeTextAllowed: boolean; state: string; createdAt: number; updatedAt: number
}

export interface OperationPlan {
  planId: string
  category: string
  action: string
  instanceId: string
  dangerous: boolean
  expiresAt: string
  details: Record<string, unknown>
}

export interface Operation {
  id: string
  category: string
  action: string
  instanceId: string
  state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  progress: number
  message: string
  startedAt: string
  finishedAt?: string
  result?: Record<string, unknown>
  error?: string
}

export interface StreamEvent {
  type: string
  operationId?: string
  state?: string
  progress?: number
  message?: string
  error?: string
  at?: string
  data?: unknown
}
