# Maintaining Archi MCP Server

> **Inherited GUI/HTTP maintenance guide.** Axioma's current headless and Live
> build/acceptance paths are documented in the [development guide](docs/headless-development.md).

Notes for the project maintainer. This is **not** a contribution guide — the public repository
does not accept pull requests. For building and testing from source, see [BUILDING.md](BUILDING.md).

## Change discipline

**Any commit that changes a default value, a configuration/preference key, or what the server
accepts (tool inputs, request shape, accepted values) MUST update the README configuration table
and `CHANGELOG.md` in the same commit.**

Keeping the code, the documented configuration surface, and the changelog in lockstep — within a
single commit — prevents the docs from drifting out of sync with what the server actually does.
If a change ships without the matching README/CHANGELOG update, treat it as incomplete and fix it
before moving on.

## Releases

- Build the installable plug-in with `tools/ci/package-plugin.sh` (see [BUILDING.md](BUILDING.md));
  the output is `build/dist/<bundle>_<version>.archiplugin`.
- The CI Archi version is pinned (`ARCHI_VERSION` in `.github/workflows/ci.yml`). Bump it
  deliberately — test a new Archi first via the workflow's `archi_version` dispatch input, then
  change the one line to adopt it.
