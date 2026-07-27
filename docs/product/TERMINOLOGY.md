# MCAC product terminology

This glossary is the source of truth for player-facing `zh-CN` and `en-US` terminology. Protocol
identifiers, file names, status enums and exact Tool names remain unchanged, but the surrounding
copy must explain them in the selected language.

| en-US | zh-CN | Meaning |
|---|---|---|
| AI companion | AI 伙伴 | The authenticated Minecraft body controlled through MCAC |
| Runtime service | 运行服务 | The isolated local MCAC process for one instance |
| Model service | 模型服务 | A configured model API provider; `rules` remains an exact mode ID |
| External AI controller | 外部 AI 控制器 | The only high-level planner/reasoner, such as Hermes or another compatible controller |
| Skill | 技能 | A declarative, reviewable Task Graph resource |
| Task Graph | 任务图 | A deterministic execution graph authored outside MCAC |
| Compatibility | 兼容层 | Declarative packs, fingerprints, resolution, evidence, and lifecycle management |
| Compatibility pack | 兼容包 | An immutable `.mcac-compat` archive |
| Compatibility Store | 兼容存储 | The Profile- and instance-scoped immutable pack store |
| Diagnostics | 诊断 | Live evidence and bounded repair entry points |
| Search | 搜索 | A disabled-by-default, privacy-filtered, untrusted external-content channel |
| Memory | 记忆 | Reviewed and scoped durable facts; not raw chat or Search content |
| Episode capsule | 情节胶囊 | A deterministic bounded summary that is not automatically verified Memory |
| Full Bridge | 完整桥接 | Loader support with authenticated Runtime control |
| Local only | 仅本地 | Static/local controls without Runtime Bridge; exact status ID `LOCAL_ONLY` |
| Lease | 租约 | Time- and identity-bound control authority |
| Control epoch | 控制纪元 | Monotonic authority generation used to reject stale control |

## Style rules

- Use “External AI controller / 外部 AI 控制器” in player-facing prose. Use `Brain` only when
  quoting a protocol field or an existing technical identifier.
- Use “Runtime service / 运行服务” in prose. Keep `Runtime`, `Profile`, protocol versions and port
  field names when they are exact technical identifiers.
- Never translate status and protocol values such as `ACTIVE`, `LOCAL_ONLY`,
  `RECONCILIATION_REQUIRED`, `mcac-compat/1`, or Tool names.
- Fixture, Replay, Fake, Mock, GameTest, Live, and human-play evidence labels are never
  interchangeable.
