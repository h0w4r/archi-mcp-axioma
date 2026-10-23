# Headless server dependencies

New code and the inherited baseline retain the repository's MIT license.
Dependencies keep their respective licenses and notices; the Axioma
installation does not change those terms.

| Dependency | Source used |
| --- | --- |
| Archi, EMF, Equinox, SWT, GEF, and Guava | Local Archi 5.10.0 distribution |
| Java MCP SDK 0.17.2, Reactor, and Reactive Streams | Dependencies already versioned in the baseline |
| Jackson 2.16.1, SnakeYAML, JSON validators, and ITU | Dependencies already versioned in the baseline |
| SLF4J 2.0.11 | Dependency already versioned in the baseline |
| ELK 0.11.0 and Xbase 2.37.0 | Dependencies already versioned in the baseline |
| Batik 1.17, XML APIs Ext 1.3.04, XML Graphics Commons 2.9, and Commons IO 2.14.0 | Official SVG exporter included with Archi |
| psutil | Acceptance client only, not the production server |

The compiler does not download floating dependency versions. It reuses the
available libraries and fails if expected files are missing. Included JARs
retain their internal `META-INF/LICENSE*` and `NOTICE*` files. Packages include
the original repository MIT license.

Runtime preparation copies a local Archi distribution. It does not grant a
replacement license for Archi or Eclipse components. Neither installing jArchi
nor purchasing a jArchi binary is required. Existing jArchi scripts remain
independent of the server.

The headless bundle does not include Jetty or listen on a network port. Its
transport is stdio. HTTP dependencies and GUI approval controls remain in the
legacy component, not in the headless bundle.

The optional Live companion additionally uses EMF Compare and EMF Change from
the installed Archi 5.10 distribution. It does not require jArchi or a separate
network service.
