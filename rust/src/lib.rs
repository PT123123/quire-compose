//! The bridge: the Kotlin shell's one door into `quire-core`.
//!
//! Android loads this crate as `libquire_bridge.so` and resolves the four
//! exported symbols by name, so each one is `Java_dev_quire_compose_bridge_Native_*`
//! — the JVM's mangling of the Kotlin `object Native` on the other side.
//!
//! Three decisions shape everything here, and each is a reaction to a cost the
//! alternatives pay:
//!
//! * **One request, one reply, both JSON.** The alternative is an exported
//!   symbol per operation plus a JNI object per row, which means the protocol
//!   lives half in Kotlin signatures and half in Rust, and every new field
//!   touches both. One string in and one string out puts the whole contract in
//!   `session::Request`, where it can be tested without a JVM at all.
//!   (Not UniFFI: its Kotlin bindings reach the native side through JNA, and a
//!   second marshalling layer under a UI whose SPEC ranks *low RAM* above
//!   maintainability and feature count is the wrong trade. Not `#[uniffi]`
//!   derives on the core either: that would mean changing the shared crate to
//!   suit one shell, and the core's rule is that nothing under its `src/` may
//!   know about a consumer.)
//! * **A handle is a pointer to a boxed session**, not an entry in a global
//!   registry: no global state, no map to leak, and `close` genuinely frees.
//! * **A panic is a reply, not an abort.** A panic crossing an FFI boundary is
//!   undefined behaviour and in practice kills the process, so every export
//!   catches it and returns `{"ok":false,"error":…}` — a note the shell can
//!   show beats an app that disappears.

mod org;
mod session;
mod view;
mod workspace;

/// The JNI layer is a shell around this: `Session` is the whole bridge, and the
/// tests drive it directly, with no JVM anywhere near.
pub use session::Session;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Mutex;

use jni::objects::{JObject, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;

use session::error_reply;

/// One open workspace. The mutex is not for parallelism — the UI calls from one
/// thread — but for the ticker and the lifecycle callbacks, which are the two
/// places Android may hand work to another thread (and both exist so that a
/// backgrounded app does not lose its last edit).
struct Handle(Mutex<Option<Session>>);

/// # Safety
/// `handle` must be a pointer this module produced and has not yet freed, or 0.
unsafe fn handle_ptr<'a>(handle: jlong) -> Option<&'a Handle> {
    if handle == 0 {
        None
    } else {
        Some(&*(handle as *const Handle))
    }
}

fn reply(env: &mut JNIEnv, body: String) -> jstring {
    match env.new_string(body) {
        Ok(s) => s.into_raw(),
        // Out of memory, or a detached thread: the caller reads a null back and
        // shows its own error rather than crashing on a half-built string.
        Err(_) => std::ptr::null_mut(),
    }
}

fn arg(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value).map(|s| s.into()).map_err(|e| e.to_string())
}

/// `Native.create()` — a fresh, empty handle.
#[no_mangle]
pub extern "system" fn Java_dev_quire_compose_bridge_Native_create(
    _env: JNIEnv,
    _this: JObject,
) -> jlong {
    Box::into_raw(Box::new(Handle(Mutex::new(None)))) as jlong
}

/// `Native.open(handle, dataDir)` — open (or create) the library and answer
/// with the first view. The Kotlin side calls this before it has anything to
/// draw, so a failure here is a string it can put in an error screen.
#[no_mangle]
pub extern "system" fn Java_dev_quire_compose_bridge_Native_open(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    data_dir: JString,
) -> jstring {
    let dir = match arg(&mut env, &data_dir) {
        Ok(dir) => dir,
        Err(e) => return reply(&mut env, error_reply(&format!("bad data dir: {e}"))),
    };
    let body = catch_unwind(AssertUnwindSafe(|| {
        let Some(h) = (unsafe { handle_ptr(handle) }) else {
            return error_reply("null handle");
        };
        let mut guard = h.0.lock().unwrap_or_else(|e| e.into_inner());
        match Session::open(&dir) {
            Ok(session) => {
                let body = session.view_reply();
                *guard = Some(session);
                body
            }
            Err(e) => error_reply(&e),
        }
    }))
    .unwrap_or_else(|_| error_reply("the bridge panicked while opening the library"));
    reply(&mut env, body)
}

/// `Native.dispatch(handle, request)` — one request in, one reply out.
#[no_mangle]
pub extern "system" fn Java_dev_quire_compose_bridge_Native_dispatch(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    request: JString,
) -> jstring {
    let body = match arg(&mut env, &request) {
        Ok(body) => body,
        Err(e) => return reply(&mut env, error_reply(&format!("bad request: {e}"))),
    };
    let out = catch_unwind(AssertUnwindSafe(|| {
        let Some(h) = (unsafe { handle_ptr(handle) }) else {
            return error_reply("null handle");
        };
        let mut guard = h.0.lock().unwrap_or_else(|e| e.into_inner());
        match guard.as_mut() {
            Some(session) => session.dispatch(&body),
            None => error_reply("the library is not open"),
        }
    }))
    .unwrap_or_else(|_| error_reply("the bridge panicked handling that request"));
    reply(&mut env, out)
}

/// `Native.close(handle)` — flush and free. Called from `onDestroy`, so the
/// queue is written before the process goes away; a session that is only ever
/// backgrounded is flushed by the `flush` request instead and comes back here
/// with an empty queue.
#[no_mangle]
pub extern "system" fn Java_dev_quire_compose_bridge_Native_close(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // The box is reclaimed whoever got here first: a panic inside the session
    // must not turn into a leaked handle plus a lying `close`.
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        let boxed = Box::from_raw(handle as *mut Handle);
        let mut guard = boxed.0.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(session) = guard.take() {
            let _ = session.flush();
        }
    }));
}
