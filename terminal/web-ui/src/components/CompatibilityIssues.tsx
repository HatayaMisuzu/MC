import { useI18n } from '../i18n/I18nContext'
import type { TranslationKey } from '../i18n/resources'

const messages: Record<string, TranslationKey> = {
  TARGET_UNSUPPORTED: 'install.incompatibleBody',
  LOADER_VERSION_UNSUPPORTED: 'install.loaderRequirement',
  JAVA_REQUIREMENT_UNCONFIRMED: 'install.javaRequirement',
  DEPENDENCY_MISSING_OR_INCOMPATIBLE: 'install.dependencyRequirement',
}

export function CompatibilityIssues({ issues }: { issues: string[] }) {
  const { t } = useI18n()
  if (issues.length === 0) return null
  return <ul>{issues.map((issue) => {
    const separator = issue.indexOf(':')
    const code = separator < 0 ? issue : issue.slice(0, separator)
    const requirement = separator < 0 ? '' : issue.slice(separator + 1).trim()
    return <li key={issue}>{messages[code] ? t(messages[code], { requirement }) : issue}</li>
  })}</ul>
}
