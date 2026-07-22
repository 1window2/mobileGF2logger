# Contributing to GF2log

Thank you for contributing to GF2log. Keep changes focused, reviewable, and safe for users who run the project on their own devices.

## Development workflow

1. Create a branch for the change.
2. Keep commits limited to one logical purpose.
3. Add or update tests when behavior changes.
4. Do not commit packet captures, account identifiers, authentication tokens, private keys, certificates, generated logs, or other user data.
5. Run the relevant checks before opening a pull request and describe the checks in the pull request.

## Commit message guidelines

This project follows the [Conventional Commits](https://www.conventionalcommits.org/) specification.

### `feat:` - New feature

Use for a new feature or functional capability.

- `feat: add per-app VPN capture service`
- `feat: parse weapon inventory payloads`

### `fix:` - Bug fix

Use when correcting a defect or restoring broken functionality.

- `fix: preserve fragmented TCP payloads across reads`
- `fix: stop the capture service when VPN permission is revoked`

### `docs:` - Documentation

Use for documentation-only changes that do not modify production behavior.

- `docs: document Android VPN setup`
- `docs: add protocol framing notes`

### `chore:` - Chores and configuration

Use for build, development environment, dependency, or auxiliary-tool changes.

- `chore: configure Android lint checks`
- `chore: update protobuf compiler`

### `refactor:` - Refactoring

Use for internal structural, readability, or performance improvements that do not add a feature or fix a defect.

- `refactor: separate TCP reassembly from payload parsing`
- `refactor: move export logic into data sinks`

### `style:` - Code style

Use for formatting-only changes that do not change behavior.

- `style: apply Kotlin formatting`

### `test:` - Tests

Use when adding missing tests or correcting existing tests.

- `test: add fragmented message parser cases`
- `test: cover malformed protobuf payloads`

## Commit format

Use an imperative, concise subject:

```text
<type>: <description>
```

Add a body when the reason, trade-offs, or migration impact are not obvious. Reference an issue or breaking change when applicable. Do not mix unrelated formatting, refactoring, and behavior changes in one commit.

## Security and privacy

- Capture only traffic the device owner has explicitly authorized the app to inspect.
- Prefer Android per-app VPN routing so unrelated application traffic is excluded.
- Parse and retain only the fields required by the feature.
- Keep capture and parsing on-device by default.
- Treat raw traffic, session tokens, player identifiers, and exported records as sensitive data.
- Do not add certificate-pinning bypasses, anti-cheat bypasses, credential extraction, traffic modification, or automation that creates an unfair gameplay advantage.
- Report suspected vulnerabilities privately to the maintainers rather than publishing secrets or exploitable packet samples.

## Pull requests

Pull requests should explain:

- What changed and why.
- The affected capture, parsing, storage, or UI components.
- How the change was tested.
- Any privacy, security, protocol-version, battery, or performance impact.
- Whether the change alters stored or exported data formats.
