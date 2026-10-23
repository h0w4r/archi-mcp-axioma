# Axioma documentation

[Project home](../README.md)

Start with the workflow you need. These guides describe **Archi MCP Axioma's
headless server** and its optional Live companion, not the original GUI/HTTP
server.

## Use Axioma

| Guide | What it answers |
| --- | --- |
| [Getting started](headless-getting-started.md) | What do I install, how do I connect my client, and how do I verify it? |
| [Tool reference](headless-tools.md) | Which tools exist, what IDs do they need, and how do I submit a batch? |
| [Operations and model safety](headless-operations.md) | When are changes saved, what happens during a conflict, and how do I recover? |
| [Axioma Live](../live/README.md) | How do I watch changes in Archi without losing unsaved work? |
| [Compiled-package guide](../headless/PAQUETE.md) | How do I use a compiled headless ZIP rather than build it? |

## Understand and develop Axioma

| Guide | What it covers |
| --- | --- |
| [Headless architecture](headless-architecture.md) | Protocol, core, persistence, native adaptation, rendering, and Live boundaries |
| [Development](headless-development.md) | Real acceptance tests, build outputs, and packaging |
| [Dependencies](../headless/DEPENDENCIAS.md) | Native libraries, reused dependencies, licensing, and jArchi independence |
| [Changelog](../CHANGELOG.md) | Project changes |
| [Security](../SECURITY.md) | Vulnerability reporting |

## Choose the right component

- **Headless server:** the current `axioma_*` tools over stdio. No open workbench
  or GUI approval is needed.
- **Live companion:** a separate, optional Archi plug-in for refreshing models
  already open in the GUI. It does not run the MCP server.
- **jArchi:** an independent scripting plug-in. An existing installation does
  not need to become an MCP bridge.
- **Legacy server:** the inherited `net.vheerden.archi.mcp` GUI/HTTP implementation.
  Its tools, approval behavior, and resources are a different contract.

## Legacy and inherited technical references

The [legacy README](../README-LEGADO.md), [legacy technical index](index.md),
[original build guide](../BUILDING.md), and [original maintenance guide](../MAINTAINING.md)
are retained for the upstream component. Do not apply their HTTP configuration
or GUI approval instructions to the headless server.

Some inherited references are useful when studying the geometry code Axioma
reuses, including the [coordinate model](coordinate-model.md),
[layout engine](layout-engine.md), and [routing pipeline](routing-pipeline.md).
Their descriptions of the original server's wider API are not a promise that
those endpoints exist in Axioma.

[Get started](headless-getting-started.md) · [Project home](../README.md)
