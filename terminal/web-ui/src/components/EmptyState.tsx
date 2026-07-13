import type { ReactNode } from 'react'

export function EmptyState({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty-state">
      <h3>{title}</h3>
      <p>{children}</p>
      {action}
    </div>
  )
}
