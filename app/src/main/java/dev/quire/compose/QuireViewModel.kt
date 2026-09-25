package dev.quire.compose

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.quire.compose.bridge.Bridge
import dev.quire.compose.bridge.Native
import dev.quire.compose.bridge.Reply
import dev.quire.compose.bridge.View
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The shell's one state holder: the last view the bridge sent, and every
 * operation the UI can ask for.
 *
 * **One call at a time.** The native session is a single workspace behind a
 * mutex, so a second caller would not corrupt it — it would just observe
 * replies in an order it did not choose, and the UI would settle on whichever
 * landed last. Every call therefore goes through one single-threaded dispatcher:
 * the ordering the UI sees is the ordering it asked for.
 *
 * **One writer.** The Rust side owns the debounced queue; this class owns the
 * clock that drains it (a tick a second while the app is in front, a flush when
 * it goes away, a final flush on close). Android gives an app no reliable
 * "about to die" callback, so the honest design is "write early and often".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuireViewModel(application: Application) : AndroidViewModel(application) {

    private val bridge = Bridge(Native.create())
    private val bridgeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** The last full view, or null until the library has opened. */
    var view by mutableStateOf<View?>(null)
        private set

    /** The last failure, shown as a bar; cleared by the next successful call. */
    var error by mutableStateOf<String?>(null)
        private set

    /** A one-shot startup notice (a recovered backup), dismissed by the user. */
    var notice by mutableStateOf<String?>(null)
        private set
    private var noticeTaken = false

    /**
     * The block a reply just created, waiting for the caret.
     *
     * The new row's id is not knowable before its reply — the core allocates it —
     * so the UI cannot ask for it, it has to be told. The id is found by
     * difference (what is in the new page that was not in the old one), which is
     * exact rather than the usual "the highest id, probably" guess.
     */
    var pendingFocus by mutableStateOf<Long?>(null)
        private set

    fun consumeFocus() {
        pendingFocus = null
    }

    private var foreground = false
    private var closed = false

    init {
        viewModelScope.launch {
            // `internalDataPath` on the Rust side was `<files>/Quire`, the same
            // folder name a desktop install makes, so a library can be carried
            // between the two shells without the store noticing which it is on.
            val dataDir = File(getApplication<Application>().filesDir, "Quire").absolutePath
            apply(withContext(bridgeDispatcher) { bridge.open(dataDir) })
        }
        // The writer's clock. Ticking while backgrounded would keep a wakeup
        // every second for a queue that is already empty, so the loop runs but
        // the work does not.
        viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                if (foreground) withContext(bridgeDispatcher) { bridge.tick() }
            }
        }
    }

    /** The app came to the front (tick) or went away (write now). */
    fun setForeground(front: Boolean) {
        if (front == foreground) return
        foreground = front
        if (!front) flush()
    }

    fun dismissNotice() {
        notice = null
    }

    fun dismissError() {
        error = null
    }

    // ─── pages ──────────────────────────────────────────────────────────────

    fun openPage(page: Long) = act { bridge.openPage(page) }

    fun createPage(parent: Long?, title: String = "") = act { bridge.createPage(parent, title) }

    fun renamePage(page: Long, title: String) = act { bridge.renamePage(page, title) }

    fun deletePage(page: Long) = act { bridge.deletePage(page) }

    fun toggleFavorite(page: Long) = act { bridge.toggleFavorite(page) }

    fun toggleExpanded(page: Long) = act { bridge.toggleExpanded(page) }

    fun setPageLocked(page: Long, locked: Boolean) = act { bridge.setPageLocked(page, locked) }

    fun setTheme(theme: String) = act { bridge.setTheme(theme) }

    // ─── blocks ─────────────────────────────────────────────────────────────

    /** A keystroke. Deliberately `Reply.Done`: nothing comes back to redraw. */
    fun setBlockText(block: Long, text: String) = act { bridge.setBlockText(block, text) }

    fun insertBlockAfter(block: Long, kind: String = KIND_PARAGRAPH, text: String = "") =
        actCreatingBlock { bridge.insertBlockAfter(block, kind, text) }

    fun appendBlock(kind: String = KIND_PARAGRAPH, text: String = "") =
        actCreatingBlock { bridge.appendBlock(kind, text) }

    fun deleteBlock(block: Long) = act { bridge.deleteBlock(block) }

    fun setBlockKind(block: Long, kind: String) = act { bridge.setBlockKind(block, kind) }

    fun toggleChecked(block: Long) = act { bridge.toggleChecked(block) }

    fun toggleFold(block: Long) = act { bridge.toggleFold(block) }

    fun moveBlock(block: Long, delta: Int) = act { bridge.moveBlock(block, delta) }

    fun indentList(block: Long) = act { bridge.indentList(block) }

    fun outdentList(block: Long) = act { bridge.outdentList(block) }

    fun undo() = act { bridge.undo() }

    fun redo() = act { bridge.redo() }

    // ─── the plumbing ───────────────────────────────────────────────────────

    /**
     * Run one bridge call off the main thread, then land its reply on it.
     *
     * The failure path keeps the last good view: a rejected keystroke on a page
     * that just became read-only must not blank the document behind an error.
     */
    private fun act(action: () -> Reply) {
        viewModelScope.launch {
            val reply = withContext(bridgeDispatcher) { runCatching(action) }
            reply
                .onSuccess(::apply)
                .onFailure { error = it.message ?: it.toString() }
        }
    }

    /** `act`, plus: put the caret in the block this reply created. */
    private fun actCreatingBlock(action: () -> Reply) {
        val known = view?.blocks?.mapTo(HashSet()) { it.id } ?: emptySet()
        viewModelScope.launch {
            val reply = withContext(bridgeDispatcher) { runCatching(action) }
            reply
                .onSuccess { result ->
                    apply(result)
                    if (result is Reply.Updated) {
                        result.view.blocks.firstOrNull { it.id !in known }?.let { pendingFocus = it.id }
                    }
                }
                .onFailure { error = it.message ?: it.toString() }
        }
    }

    private fun apply(reply: Reply) {
        when (reply) {
            is Reply.Updated -> {
                view = reply.view
                error = null
                if (!noticeTaken && reply.view.notice != null) {
                    notice = reply.view.notice
                    noticeTaken = true
                }
            }
            Reply.Done -> error = null
            is Reply.Failed -> error = reply.message
        }
    }

    private fun flush() {
        viewModelScope.launch { withContext(bridgeDispatcher) { bridge.flush() } }
    }

    /**
     * Write, then free. Blocking on purpose: the handle must not be released
     * while a call is still in flight, and the queue at this moment is the last
     * edit of the session — the one thing losing is unforgivable. It costs one
     * SQLite write on the way out of the app.
     */
    override fun onCleared() {
        if (!closed) {
            closed = true
            runBlocking(bridgeDispatcher) {
                runCatching { bridge.flush() }
                bridge.close()
            }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}

/** The short stable string `quire-core` stores a paragraph as. */
const val KIND_PARAGRAPH = "paragraph"
