# Security Policy

## Reporting a Vulnerability

**Report privately — do NOT open a public issue.**

- **Preferred**: GitHub Private Vulnerability Reporting
  (Security tab → "Report a vulnerability")
- **Also accepted**: encrypted email to huaitian.behinder@gmail.com,
  GPG key `125F 7101 1717 0318 A318  F9C4 7FA3 DE0E 6B42 E01A`

Include: app version (Settings → About), device model, Android version,
ROM, and steps to reproduce.

## Scope

The detection logic (callbacks, observers, polling paths), permission
handling, and anything that could crash the app or leak user data
(e.g., the notification-content handling).

Out of scope: detection gaps against framework-level or privileged
attackers documented in the README ("limited capability against
system framework-level behaviors") — those are documented design
boundaries, not vulnerabilities.

## What to expect

Non-commercial personal project, maintained best-effort:
**no guaranteed response time**. Confirmed issues will be fixed
and announced via GitHub Security Advisories.

## Safe harbor

Good-faith research reported privately will not face legal action.