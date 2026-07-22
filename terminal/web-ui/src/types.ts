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

export interface BrainStatus {
  activeControllerId: string
  health: { status: string; adapter: string; detail: string; checkedAt: string }
}
export interface BrainToolAudit {
  callId: string; toolName: string; success: boolean; code: string; terminal: boolean
  observation?: Record<string, unknown>
}
export interface BrainSessionAudit {
  sessionId: string; controllerId: string; provider: string; state: string; lastCode: string
  createdAt: string; updatedAt: string; toolCalls: BrainToolAudit[]
}
export interface MemoryFact {
  memoryId: string; kind: string; key: string; value: unknown; verified: boolean
  confidence: number; source: string; expiresAt?: string; createdAt: string; updatedAt: string
}
export interface MemorySuggestion {
  suggestionId: string; companionId: string; kind: string; key: string; value: unknown
  confidence: number; status: string; source: string; brainSessionId: string
  expiresAt: string; createdAt: string; updatedAt: string
  capsuleId?: string; conflictsWithVerified: boolean; conflictingMemoryId?: string
}
export interface EpisodeCapsule {
  episodeId: string; companionId: string; brainSessionId: string; startedAt: string; endedAt: string
  taskSummaries: unknown[]; verifiedWorldChanges: unknown[]; verifiedInventoryChanges: unknown[]
  verifiedLocations: unknown[]; askUserDecisions: unknown[]; userConfirmedChoices: unknown[]
  failureCategories: string[]; evidenceRefs: unknown[]; sourceSha: string; createdAt: string
}
export interface MemorySnapshot {
  companionId: string; byKind: Record<string, MemoryFact[]>; suggestions?: MemorySuggestion[]
  episodeCapsules?: EpisodeCapsule[]
}
export interface TaskGraphExecution {
  executionId: string; companionId: string; graphId: string; graphVersion: string
  state: string; currentNodeId?: string; completedNodeCount: number
  resultCode: string; revision: number; createdAt: string; updatedAt: string
}
export interface TaskGraphSnapshot { companionId: string; executions: TaskGraphExecution[] }
export interface SkillVersion {
  requestId: string; companionId: string; skillId: string; version: number; format: string
  document: string; sha256: string; permissions: unknown; provenance: unknown; validation: unknown
  status: string; statusReason?: string; controllerId: string; brainSessionId: string
  approvedBy?: string; approvedAt?: string; createdAt: string; updatedAt: string
}
export interface BuiltinSkill {
  skillId: string; format: string; sha256: string; trust: 'BUILT_IN'
}
export interface WorkspaceRetainedVersion { version: number; sha256: string; sizeBytes: number }
export interface WorkspaceDraft {
  logicalPath: string; version: number; sha256: string; sizeBytes: number; updatedAt: string
  document: string; retainedVersions: WorkspaceRetainedVersion[]
}
export interface SkillSnapshot {
  companionId: string; builtins: BuiltinSkill[]; drafts: WorkspaceDraft[]; versions: SkillVersion[]
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
