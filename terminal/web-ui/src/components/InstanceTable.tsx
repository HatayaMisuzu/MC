import { useI18n } from '../i18n/I18nContext'
import type { Instance } from '../types'
import { StatusBadge } from './StatusBadge'

export function InstanceTable({ instances, selectedId, onSelect }: {
  instances: Instance[]; selectedId: string; onSelect: (id: string) => void
}) {
  const { t } = useI18n()
  return <div className="table-scroll"><table className="data-table">
    <thead><tr><th>{t('instances.instance')}</th><th>Minecraft</th><th>Loader</th>
      <th>{t('instances.gameDirConfidence')}</th><th>Java</th>
      <th>{t('instances.install')}</th><th>{t('instances.mode')}</th></tr></thead>
    <tbody>{instances.map((instance) => <tr key={instance.id}
      className={selectedId === instance.id ? 'selected' : ''} onClick={() => onSelect(instance.id)}>
      <td><span className="instance-name"><input type="radio" readOnly
        checked={selectedId === instance.id} />{instance.name}</span></td>
      <td>{instance.minecraftVersion}</td><td>{instance.loader} {instance.loaderVersion}</td>
      <td><StatusBadge value={instance.confidence} /></td>
      <td>{instance.javaConfigured || t('instances.javaRequired', { version: instance.javaRequired })}</td>
      <td><StatusBadge value={instance.installed ? 'PASS' : 'WAITING'} /></td>
      <td><StatusBadge value={instance.mode} /></td>
    </tr>)}</tbody>
  </table></div>
}
