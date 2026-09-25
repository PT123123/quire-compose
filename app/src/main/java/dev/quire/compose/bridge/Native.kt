package dev.quire.compose.bridge

/**
 * The four symbols `libquire_bridge.so` exports.
 *
 * Every call is one request string in and one reply string out, so the protocol
 * lives in Rust (`quire-bridge/src/session.rs`) and this file stays a set of
 * declarations. The `external` methods compile to instance methods on this
 * singleton, which is exactly the JNI name the Rust side exports:
 * `Java_dev_quire_compose_bridge_Native_dispatch` and friends.
 *
 * A handle is an opaque `long` naming one open library. It is created by
 * [create] and is only valid until [close]; nothing here checks that, because
 * the Kotlin owner ([Bridge]) is the thing that guarantees it.
 */
object Native {
    init {
        System.loadLibrary("quire_bridge")
    }

    external fun create(): Long

    /** Open (or create) the library under `dataDir`; the reply carries the first view. */
    external fun open(handle: Long, dataDir: String): String

    external fun dispatch(handle: Long, request: String): String

    external fun close(handle: Long)
}
