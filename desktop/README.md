# Logic Desktop

Native Linux wrapper around Logic. Packages the existing Spring Boot jar
(with the frontend already embedded, same as the Docker image) together with
a `jlink`-trimmed JRE inside a Tauri shell, so it runs as a normal desktop
app with no separate server to start.

On launch, the Rust process:
1. Spawns `resources/jre/bin/java -jar resources/app.jar` as a child process
   against a per-user app-data directory (H2 database, Lucene index,
   uploads), with auth disabled (single-user local process).
2. Polls `127.0.0.1:8080` (falling back to a free port if 8080 is taken,
   e.g. by the Docker container running at the same time) until the backend
   responds.
3. Navigates the window from the loading placeholder to the running
   backend's URL.
4. Kills the backend process when the window closes.

## Build

Requires: `node`/`npm`, `mvn`, `cargo`/`rustc`, and a JDK with `jlink`/`jdeps`
**and its jmods** (on openSUSE: `sudo zypper install java-25-openjdk-jmods`
— the jmods package isn't pulled in by the base JDK package).

```
./build.sh
```

Produces an AppImage and a `.deb` under
`desktop/src-tauri/target/release/bundle/`.
