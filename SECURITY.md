# Security Policy

## Supported versions

Only the latest release receives security fixes. The plugin ships in two builds with
different server requirements; both are supported.

| Build | Supported Minecraft | Required Java |
|---|---|---|
| Standard (`Let's Learn English!-<version>.jar`) | 1.21.4 – 1.21.11 | 21+ |
| 26.x (`Let's Learn English!-<version>-mc26.jar`) | 26.1+ | 25+ |

## Reporting a vulnerability

**Please do not open a public issue.**

Use GitHub's private vulnerability reporting:
**[Report a vulnerability](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/security/advisories/new)**

If that is unavailable to you, contact the maintainer at the address on the
[GitHub profile page](https://github.com/sqkl520) and say you are reporting a
security issue — do not include exploit details in the first message.

### What to include

- Affected version(s)
- A description of the impact — what an attacker gains
- Steps to reproduce, or a proof of concept
- Any preconditions (admin access required? specific config? multiplayer network?)

### What to expect

This is a hobby project maintained in spare time, so please treat these as best-effort:

- **Acknowledgement within 7 days.** If you hear nothing in 7 days, ping the issue again —
  it means the message was missed, not that it was ignored.
- **An assessment within 30 days** — whether it is a real vulnerability, and roughly when
  a fix might land.
- Public credit in the release notes when a fix ships, unless you prefer to remain anonymous.

## Scope

In scope — things that affect servers running this plugin:

- Remote code execution, path traversal, arbitrary file write
- Authentication / authorization bypass in the admin commands or the statistics HTTP server
- Cross-site scripting or request forgery in anything the plugin serves over HTTP
- Leakage of player data across servers in a proxy network
- Injection into the AI provider, or leakage of the configured API key

Out of scope:

- Anything requiring the attacker to already be a server operator with `/op`
- Vulnerabilities in the Minecraft server itself, or in Paper/Spigot
- Vulnerabilities in third-party dependencies that do not have a practical impact here
  (please report those upstream first — but do tell us if this plugin uses them unsafely)
- The plugin running only on a server the attacker already fully controls
- Denial of service from a player who is allowed to spam commands — rate limits are a
  config knob, not a security boundary

## Known design decisions

These are documented choices, not vulnerabilities:

- **The AI chat feature sends player messages to a third-party API** (DeepSeek by default).
  Consent handling and the model endpoint are configurable; see the AI section of the README.
- **The statistics HTTP server binds to `127.0.0.1` by default.** Exposing it externally
  requires changing `bind-address`, and the plugin refuses to start with a default password
  when bound to a non-loopback address.
- **Message templates use `&` colour codes and are config-administered**, so a server owner
  can put arbitrary text in front of players. That is by design.
