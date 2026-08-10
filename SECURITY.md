# Security policy

## Supported scope

Security fixes are evaluated for the current `main` branch and the latest published MCAC release.
Older snapshots and unsupported Minecraft/Loader combinations may require upgrading before a fix
can be applied. Support status is documented in `docs/PRODUCT_STATUS.md`.

## Reporting a vulnerability

Do not publish exploit details, credentials, private world data, or an unpatched high-impact issue
in a public GitHub issue. If the repository Security tab offers a private vulnerability-reporting
form, use it. Otherwise, contact the maintainer through their GitHub profile to request a private
reporting channel without including sensitive details in the initial public message.

Include the affected version or commit, environment and Loader, reproduction steps or a minimal
proof of concept, expected and actual behavior, security impact, and any known mitigations. Remove
launcher credentials, API keys, personal paths, and private Minecraft data from logs and bundles.

If a secret or API key may have leaked, revoke or rotate it at the provider immediately. Do not wait
for a code fix, and do not commit the replacement secret to the repository. Response and remediation
time depend on severity, reproducibility, and maintainer availability; no fixed SLA is promised.
