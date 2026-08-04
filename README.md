# bascuuu-leveling
Bukkit Plugin

## Descargar el JAR automáticamente

El repositorio genera un artefacto JAR en GitHub Actions en cada push o pull request a la rama `main`.

- Archivo: `target/*.jar`
- Workflow: `.github/workflows/build.yml`

Puedes descargar el JAR desde la pestaña **Actions** del repositorio, seleccionando el workflow `Build JAR` y buscando el artefacto `bascuuu-leveling-jar`.

## Economía compatible

Este plugin usa la economía registrada vía Vault API.

- Compatible con `Vault2.0` de https://github.com/shalom25/Vault2.0
- No es necesario el antiguo `Vault.jar` si se usa `Vault2.0`
- Solo basta con que haya un proveedor de economía registrado en el servidor para que las mejoras se puedan comprar con dinero real en el servidor
