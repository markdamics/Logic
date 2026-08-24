#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::net::{TcpListener, TcpStream};
use std::process::{Child, Command};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use tauri::{Manager, Url};

struct BackendProcess(Mutex<Option<Child>>);

/// Prefer 8080 (matches the Docker deployment, easy to recognize in logs);
/// fall back to an OS-assigned free port if something else already has it
fn pick_port() -> u16 {
    match TcpListener::bind("127.0.0.1:8080") {
        Ok(_listener) => 8080,
        Err(_) => {
            let listener = TcpListener::bind("127.0.0.1:0").expect("no free TCP port available");
            listener.local_addr().unwrap().port()
        }
    }
}

fn wait_for_backend(port: u16, timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    let addr = format!("127.0.0.1:{port}");
    while Instant::now() < deadline {
        if TcpStream::connect(&addr).is_ok() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    false
}

fn main() {
    tauri::Builder::default()
        .manage(BackendProcess(Mutex::new(None)))
        .setup(|app| {
            let handle = app.handle().clone();
            let resource_dir = handle
                .path()
                .resource_dir()
                .expect("failed to resolve resource dir");
            let data_dir = handle
                .path()
                .app_data_dir()
                .expect("failed to resolve app data dir");
            std::fs::create_dir_all(&data_dir).expect("failed to create app data dir");

            let java_bin = resource_dir.join("jre/bin/java");
            let jar = resource_dir.join("app.jar");
            let port = pick_port();

            let child = Command::new(&java_bin)
                .arg("-jar")
                .arg(&jar)
                .arg(format!("--server.port={port}"))
                .arg(format!(
                    "--spring.datasource.url=jdbc:h2:file:{}/logsources;AUTO_SERVER=TRUE",
                    data_dir.display()
                ))
                .env("SEARCH_INDEX_DIR", data_dir.join("search-index"))
                .env("UPLOADS_DIR", data_dir.join("uploads"))
                .env("AUTH_ENABLED", "false")
                .spawn()
                .expect("failed to start the Logic backend process");

            *handle.state::<BackendProcess>().0.lock().unwrap() = Some(child);

            let window = app
                .get_webview_window("main")
                .expect("main window not found");
            std::thread::spawn(move || {
                if wait_for_backend(port, Duration::from_secs(30)) {
                    let url = Url::parse(&format!("http://127.0.0.1:{port}"))
                        .expect("failed to build backend URL");
                    let _ = window.navigate(url);
                } else {
                    eprintln!("Logic backend did not become ready within 30s");
                }
            });

            Ok(())
        })
        .on_window_event(|window, event| {
            // Kill the backend when the window closes so it doesn't linger
            // as an orphaned process holding the H2 file lock.
            if let tauri::WindowEvent::Destroyed = event {
                if let Some(state) = window.app_handle().try_state::<BackendProcess>() {
                    if let Some(mut child) = state.0.lock().unwrap().take() {
                        let _ = child.kill();
                        let _ = child.wait();
                    }
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running the Logic desktop application");
}
