import type { Instance } from '../types'
import { StatusBadge } from './StatusBadge'

export function InstanceTable({ instances, selectedId, onSelect }: { instances: Instance[]; selectedId: string; onSelect: (id: string) => void }) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead><tr><th>实例</th><th>Minecraft</th><th>Loader</th><th>gameDir 置信度</th><th>Java</th><th>安装</th><th>模式</th></tr></thead>
        <tbody>
          {instances.map((instance) => (
            <tr key={instance.id} className={selectedId === instance.id ? 'selected' : ''} onClick={() => onSelect(instance.id)}>
              <td><span className="instance-name"><input type="radio" readOnly checked={selectedId === instance.id} />{instance.name}</span></td>
              <td>{instance.minecraftVersion}</td><td>{instance.loader} {instance.loaderVersion}</td>
              <td><StatusBadge value={instance.confidence} /></td>
              <td>{instance.javaConfigured ? instance.javaConfigured : `需要 Java ${instance.javaRequired}`}</td>
              <td><StatusBadge value={instance.installed ? 'PASS' : 'WAITING'} /></td>
              <td><StatusBadge value={instance.mode} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
