# Dependencias del servidor headless

El código nuevo y la línea base conservan la licencia MIT del repositorio.
Las dependencias mantienen sus respectivas licencias y avisos. La distribución
de Axioma no cambia las condiciones de las bibliotecas incluidas.

| Dependencia | Procedencia utilizada |
| --- | --- |
| Archi, EMF, Equinox, SWT, GEF y Guava | Distribución instalada de Archi 5.10.0 |
| SDK Java MCP 0.17.2, Reactor y Reactive Streams | Bibliotecas ya versionadas de la línea base |
| Jackson 2.16.1, SnakeYAML, validadores JSON e ITU | Bibliotecas ya versionadas de la línea base |
| SLF4J 2.0.11 | Biblioteca ya versionada de la línea base |
| ELK 0.11.0 y Xbase 2.37.0 | Bibliotecas ya versionadas de la línea base |
| Batik 1.17, XML APIs Ext 1.3.04, XML Graphics Commons 2.9 y Commons IO 2.14.0 | Exportador SVG oficial incluido con Archi |
| psutil | Sólo el cliente de aceptación, no el servidor de producción |

El compilador no descarga una versión flotante de estas dependencias. Reutiliza
las bibliotecas disponibles y falla si faltan los archivos esperados. Los JAR
incluidos conservan sus avisos internos `META-INF/LICENSE*` y `NOTICE*`.
El paquete incluye la licencia MIT original del repositorio.

La preparación del runtime copia una distribución local de Archi y no distribuye
una licencia sustitutiva de esa aplicación ni de los componentes Eclipse.
La integración no requiere instalar jArchi ni adquirir un binario de jArchi.

El núcleo del servidor no incluye Jetty ni escucha un puerto de red. El
transporte de esta distribución es stdio. Las dependencias HTTP y el control
de aprobación del complemento gráfico permanecen en la línea base, no en el
bundle headless.
