//! LAN sync: this shell's half of `quire-core::services::sync`.
//!
//! The core owns the protocol — the threads, the HTTP endpoints, UDP discovery,
//! the wire rows and the pure three-way merge — and deliberately opts out of two
//! things, because they need a live workspace: **reading the snapshot out of the
//! session** and **walking a merged snapshot back in**. Both live here, ported
//! from the desktop shell's `app::state` but shaped by what *this* session holds.
//!
//! Three decisions, and the first is the one that keeps a library safe:
//!
//! * **A snapshot must be able to round-trip, or there is no sync at all.** This
//!   build can carry pages, blocks and SPEC §四十一's three collections, and it
//!   cannot carry databases or attachments (its UI does not model them; the core
//!   exposes no public bulk write for the database layer). That is not a gap to
//!   be quiet about, because a merge reads *absence* as a deletion: a snapshot
//!   built here without databases, merged by a desktop peer whose shadow says the
//!   databases were agreed present, would delete the user's databases on both
//!   sides. So the two collections are **answered with this device's own rows by
//!   the desktop** ([`peer_carries_the_whole_library`] there) and **dropped at
//!   this door on the way in** ([`Session::sync_apply_remote`]), which is the same
//!   rule seen from the end that can hold them: neither library asks the other to
//!   account for a row it never carried. What is left to veto is the one case the
//!   two halves cannot cover — this device's *own* attachment rows, which no peer
//!   is ever shown and which would therefore be renumbered out from under the
//!   blocks that name them. That veto is checked before the engine is even started
//!   ([`Session::sync_ensure`]), and a snapshot that somehow cannot be built is
//!   answered by *dropping the reply channel* — the peer sees a failed sync, never
//!   an empty workspace.
//! * **The document half is written by `replace_all`, the organizer half by
//!   `Change`s.** `replace_all` takes a whole `PersistedState` and is exactly the
//!   right shape for "here is the merged workspace"; the organizer is not part of
//!   a `PersistedState` (a bulk replace leaves it alone on purpose), so it goes
//!   back through the same row-level `Change`s every organizer write uses. The
//!   state's `meta` and `settings` are read out first and handed back unchanged,
//!   because `replace_all` deletes those tables — and the peers table, this
//!   device's id and the per-peer shadows all live in `settings`.
//! * **The merge's fresh ids come from the snapshots, not from the session.**
//!   Seeding each allocator from `max(local, remote) + 1` is enough for
//!   uniqueness, and the reload afterwards walks every in-memory watermark past
//!   the rows it just loaded — so nothing this session mints next can collide.
//!
//! Identity, the peer book, the log and the per-peer shadow are `settings` rows
//! under the same keys the desktop and Slint shells use, so a library carried
//! between shells behaves identically.

use std::cell::Cell;
use std::net::UdpSocket;
use std::sync::mpsc::{channel, Receiver, Sender, TryRecvError};

use serde::Serialize;

use quire_core::core::organizer::{ListId, OrganizerCatalog, TaskId};
use quire_core::core::persistence::Repository;
use quire_core::core::types::Attachment;
use quire_core::core::Change;
use quire_core::services::sync::engine::{Cmd, DeviceInfo, Engine, Job, LogLine, PeerRecord};
use quire_core::services::sync::merge::{merge, MergeCtx};
use quire_core::services::sync::model::{
    SAttachment, SBlock, SNote, SPage, STask, STaskList, SyncSnapshot,
};
use quire_core::services::sync::{DISCOVERY_PORT, SYNC_PORT};

use crate::session::Session;

// ─── the settings keys, shared with the other shells ────────────────────────

const KEY_DEVICE_ID: &str = "sync.device-id";
const KEY_DEVICE_NAME: &str = "sync.device-name";
const KEY_PEERS: &str = "sync.peers";
const KEY_LOG: &str = "sync.log";
const KEY_AUTO: &str = "sync.auto";
const KEY_INTERVAL: &str = "sync.interval";
/// The measured floor, the desktop's own: a peer hammered every second is worse
/// than one synced a minute late.
const MIN_INTERVAL: u64 = 15;
const DEFAULT_INTERVAL: u64 = 60;
/// How many log lines are kept. Newest last.
const LOG_CAP: usize = 50;
/// A device is auto-synced while its announcement is recent. The announcer
/// speaks every 4 s, so this leaves room for a missed beat or two — and a peer
/// that stopped announcing is a peer whose server stopped too, which is the
/// round that would only burn the connect timeout and leave this side holding
/// 「正在同步…」 for it. The desktop's pump filters on the same window.
const RECENT_ENOUGH_SECS: u64 = 60;

fn shadow_key(peer_id: &str) -> String {
    format!("sync.shadow.{peer_id}")
}

// ─── the wire shape the page draws ──────────────────────────────────────────

/// One row of the device list: a paired peer, a device heard on the wire, or
/// this device itself (which is why `self_device` and `address` are here).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncRow {
    pub id: String,
    pub name: String,
    pub kind: String,
    /// `ip:port`, or `""` for a device not yet heard at an address.
    pub address: String,
    pub paired: bool,
    /// Heard within the last 15 seconds — the engine's own liveness window.
    pub online: bool,
    /// RFC 3339 of the last successful sync, or `""`.
    pub last_sync: String,
    /// This device: drawn first, with no verbs.
    pub self_device: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncLogRow {
    pub at: String,
    pub peer: String,
    pub ok: bool,
    pub message: String,
}

/// Everything the 同步 page draws, sent with every structural reply.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncView {
    /// Whether the engine is up — the page starts it by being opened.
    pub running: bool,
    /// Why this library cannot sync, when it cannot. `None` is the ordinary case.
    pub unsyncable: Option<String>,
    pub self_id: String,
    pub self_name: String,
    /// This device's LAN address, or `""` when there is no route.
    pub self_address: String,
    pub auto: bool,
    pub interval: u64,
    pub port: u16,
    pub discovery_port: u16,
    /// A sync cycle is in flight.
    pub busy: bool,
    /// The last word from the engine, for the page's status line.
    pub status: String,
    /// This device first, then paired peers, then merely-discovered devices.
    pub rows: Vec<SyncRow>,
    /// Newest last.
    pub log: Vec<SyncLogRow>,
}

// ─── the running engine ─────────────────────────────────────────────────────

/// The engine plus the bookkeeping the page needs. Held by the session; started
/// the first time the 同步 page is opened.
pub struct Sync {
    cmds: Sender<Cmd>,
    jobs: Receiver<Job>,
    /// Why this library cannot sync, decided once at start. See the module note:
    /// the answer cannot change afterwards, because nothing this shell does adds an
    /// attachment row, and an inbound snapshot's rows for both collections are
    /// dropped at [`Session::sync_apply_remote`]'s door rather than merged.
    unsyncable: Option<String>,
    busy: bool,
    last_auto: u64,
    status: String,
}

impl Sync {
    /// Start the engine, or decide that this library must not sync at all.
    ///
    /// The gate is deliberately *before* `Engine::start`: starting the threads
    /// would put a sync server on the LAN, and an inbound pull would then be
    /// answered with a snapshot this build cannot round-trip.
    pub fn start(session: &Session) -> Sync {
        let info = session.sync_self_info();
        let gate = session.sync_unsyncable();
        if gate.is_some() {
            // No engine, no listening socket, no announcements. `jobs` is a
            // channel nobody writes to, which is exactly what we want.
            let (_tx, rx) = channel();
            return Sync {
                cmds: {
                    let (tx, _rx) = channel();
                    tx
                },
                jobs: rx,
                unsyncable: gate,
                busy: false,
                last_auto: 0,
                status: String::new(),
            };
        }
        let (job_tx, jobs) = channel();
        let cmds = Engine::start(info, job_tx);
        Sync {
            cmds,
            jobs,
            unsyncable: None,
            busy: false,
            last_auto: 0,
            status: "已启动".to_string(),
        }
    }

    pub fn running(&self) -> bool {
        self.unsyncable.is_none()
    }
}

/// The device's own LAN address, or `None` when there is no route out.
///
/// A UDP socket that is `connect`ed and never written to: the kernel resolves
/// the route and the local end of it is the address a peer on the same network
/// can reach. No packet is sent, and no DNS lookup happens for a literal.
fn local_ip() -> Option<String> {
    let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
    sock.connect("8.8.8.8:80").ok()?;
    Some(sock.local_addr().ok()?.ip().to_string())
}

/// The one thing this build cannot carry. `None` is the ordinary case.
///
/// Attachments only, and the reason the database half left this gate is worth
/// keeping in the file: a *block* that points at a database is not a database row.
/// It arrives with the page it belongs to (`replace_all` writes it like any other
/// block), and this shell draws it from the desktop's copy of the table — so it
/// used to be enough for one round with a desktop that had a table anywhere in its
/// library to put `Some` here, and `Some` is the answer that stops the engine from
/// starting at all: no server, no announcements, no pairing, and a line telling
/// the user to sync with the desktop they had just synced with. The rows
/// themselves were never at risk from that round, because this build answers
/// `databases = []` and drops inbound database rows at the door, and the desktop
/// keeps its own copy against both silences (`peer_carries_the_whole_library`).
///
/// An attachment row is a different object: the bytes sit in this device's own
/// folder, `Job::AttachmentBytes` answers empty for them, and `replace_all`
/// deliberately leaves the attachments table alone — so a library that arrived with
/// rows in it (a desktop's file copied onto the phone) would offer the peer a row
/// with no file behind it, and renumber the blocks that name it. That is the case
/// the veto is for.
fn unsyncable(attachments: &[Attachment]) -> Option<String> {
    if !attachments.is_empty() {
        return Some(
            "这个资料库里有附件 —— 本版本还不同步附件，请用桌面端同步（或先在桌面端移除附件）"
                .to_string(),
        );
    }
    None
}

/// The id allocators the merge draws on, seeded from both snapshots.
///
/// Only the collections this build can carry are seeded from real rows; the
/// database layer's four are constants, because a snapshot this device builds
/// answers `databases = []` and every inbound snapshot has its rows dropped at
/// [`Session::sync_apply_remote`]'s door — neither half of the merge can hold a
/// database row to renumber. The attachment allocator rides the same argument, and
/// [`unsyncable`] is the clause that keeps it true for this device's own rows.
struct Allocators {
    page: Cell<u64>,
    block: Cell<u64>,
    note: Cell<u64>,
    task: Cell<u64>,
    list: Cell<u64>,
}

/// `max(ids) + 1` over two slices, so a fresh id can never collide with a row
/// the merge is about to write.
fn seed<T>(a: &[T], b: &[T], id: fn(&T) -> u64) -> u64 {
    a.iter().chain(b.iter()).map(id).max().unwrap_or(0) + 1
}

// ─── the session's half ─────────────────────────────────────────────────────

impl Session {
    // ---- settings-backed state ----

    /// This device's identity, minted once and remembered.
    ///
    /// Minted from the wall clock and this session's own address, which is the
    /// desktop's recipe: no uuid crate, and two inputs a second device on the
    /// same LAN will not share. It is a `settings` row, so a rename or a new IP
    /// does not orphan a pairing.
    pub(crate) fn sync_self_info(&self) -> DeviceInfo {
        let id = match self.settings.get(KEY_DEVICE_ID) {
            Some(v) if !v.is_empty() => v.to_string(),
            _ => {
                let nanos = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_nanos() as u64)
                    .unwrap_or(0);
                format!(
                    "{}-{:x}",
                    DeviceInfo::kind(),
                    nanos ^ (self as *const Session as u64)
                )
            }
        };
        let name = match self.settings.get(KEY_DEVICE_NAME) {
            Some(v) if !v.is_empty() => v.to_string(),
            _ => DeviceInfo::default_name(&id),
        };
        DeviceInfo {
            id,
            name,
            kind: DeviceInfo::kind(),
            port: SYNC_PORT,
        }
    }

    /// Write the identity rows if they are not there yet. Separate from the
    /// getter so the getter can be `&self`: it is called while building a view.
    fn sync_ensure_identity(&mut self) -> Result<(), String> {
        let info = self.sync_self_info();
        if self.settings.get(KEY_DEVICE_ID) != Some(info.id.as_str()) {
            self.put_sync_setting(KEY_DEVICE_ID, &info.id)?;
        }
        if self.settings.get(KEY_DEVICE_NAME) != Some(info.name.as_str())
            && self.settings.get(KEY_DEVICE_NAME).map(|v| v.is_empty()).unwrap_or(true)
        {
            self.put_sync_setting(KEY_DEVICE_NAME, &info.name)?;
        }
        Ok(())
    }

    /// One settings row, written now rather than queued: sync state is not a
    /// keystroke, and a peer table that a crash could eat is worse than a slow
    /// write.
    fn put_sync_setting(&mut self, key: &str, value: &str) -> Result<(), String> {
        self.settings.set(key, value);
        self.apply_now(vec![Change::SettingSet {
            key: key.to_string(),
            value: value.to_string(),
        }])
    }

    pub(crate) fn sync_peers(&self) -> Vec<PeerRecord> {
        self.settings
            .get(KEY_PEERS)
            .and_then(|v| serde_json::from_str(v).ok())
            .unwrap_or_default()
    }

    fn sync_set_peers(&mut self, peers: &[PeerRecord]) -> Result<(), String> {
        let json = serde_json::to_string(peers).unwrap_or_else(|_| "[]".into());
        self.put_sync_setting(KEY_PEERS, &json)
    }

    /// A sighting, or a pairing, of a device on the LAN. What the peer book
    /// already knew — the pairing, the last sync — is kept, and moves with the
    /// address the announcement came from.
    pub(crate) fn sync_note_device(
        &mut self,
        id: &str,
        name: &str,
        kind: &str,
        ip: &str,
        port: u16,
        paired: Option<bool>,
    ) -> Result<(), String> {
        if id.is_empty() {
            return Ok(());
        }
        let now = quire_core::services::sync::engine::now_unix();
        let mut peers = self.sync_peers();
        match peers.iter_mut().find(|p| p.id == id) {
            Some(p) => {
                if !name.is_empty() {
                    p.name = name.to_string();
                }
                if !kind.is_empty() {
                    p.kind = kind.to_string();
                }
                if !ip.is_empty() {
                    p.ip = ip.to_string();
                }
                if port != 0 {
                    p.port = port;
                }
                p.last_seen = now;
                if let Some(v) = paired {
                    p.paired = v;
                }
            }
            None => peers.push(PeerRecord {
                id: id.to_string(),
                name: name.to_string(),
                kind: kind.to_string(),
                ip: ip.to_string(),
                port,
                paired: paired.unwrap_or(false),
                last_seen: now,
                last_sync: String::new(),
            }),
        }
        self.sync_set_peers(&peers)
    }

    /// The last word on one attempt: `last_sync` moves only on success.
    fn sync_note_synced(&mut self, peer_id: &str, ok: bool) -> Result<(), String> {
        let now = quire_core::services::sync::engine::now_unix();
        let mut peers = self.sync_peers();
        if let Some(p) = peers.iter_mut().find(|p| p.id == peer_id) {
            p.last_seen = now;
            if ok {
                p.last_sync = quire_core::services::sync::engine::now_rfc3339();
            }
        }
        self.sync_set_peers(&peers)
    }

    pub(crate) fn sync_forget(&mut self, peer_id: &str) -> Result<(), String> {
        let peers: Vec<PeerRecord> = self
            .sync_peers()
            .into_iter()
            .filter(|p| p.id != peer_id)
            .collect();
        self.sync_set_peers(&peers)?;
        // The shadow goes with the pairing: forgetting a device and then pairing
        // it again must not merge against a stale common ancestor.
        self.put_sync_setting(&shadow_key(peer_id), "")
    }

    pub(crate) fn sync_log(&self) -> Vec<LogLine> {
        self.settings
            .get(KEY_LOG)
            .and_then(|v| serde_json::from_str(v).ok())
            .unwrap_or_default()
    }

    fn sync_log_push(&mut self, peer: &str, ok: bool, message: &str) -> Result<(), String> {
        let mut log = self.sync_log();
        log.push(LogLine {
            at: quire_core::services::sync::engine::now_rfc3339(),
            peer: peer.to_string(),
            ok,
            message: message.to_string(),
        });
        let cut = log.len().saturating_sub(LOG_CAP);
        log.drain(..cut);
        let json = serde_json::to_string(&log).unwrap_or_else(|_| "[]".into());
        self.put_sync_setting(KEY_LOG, &json)
    }

    pub(crate) fn sync_auto(&self) -> bool {
        self.settings.get(KEY_AUTO).map(|v| v == "1").unwrap_or(true)
    }

    pub(crate) fn sync_interval(&self) -> u64 {
        self.settings
            .get(KEY_INTERVAL)
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(DEFAULT_INTERVAL)
            .max(MIN_INTERVAL)
    }

    pub(crate) fn sync_set_auto(&mut self, on: bool) -> Result<(), String> {
        self.put_sync_setting(KEY_AUTO, if on { "1" } else { "0" })
    }

    pub(crate) fn sync_set_interval(&mut self, seconds: u64) -> Result<(), String> {
        let seconds = seconds.max(MIN_INTERVAL);
        self.put_sync_setting(KEY_INTERVAL, &seconds.to_string())
    }

    pub(crate) fn sync_set_name(&mut self, name: &str) -> Result<(), String> {
        let name = name.trim();
        let name = if name.is_empty() {
            DeviceInfo::default_name(&self.sync_self_info().id)
        } else {
            name.to_string()
        };
        self.put_sync_setting(KEY_DEVICE_NAME, &name)
    }

    fn sync_shadow(&self, peer_id: &str) -> Option<SyncSnapshot> {
        self.settings
            .get(&shadow_key(peer_id))
            .filter(|v| !v.is_empty())
            .and_then(|v| SyncSnapshot::from_json(v).ok())
    }

    fn sync_store_shadow(&mut self, peer_id: &str, snap: &SyncSnapshot) -> Result<(), String> {
        self.put_sync_setting(&shadow_key(peer_id), &snap.to_json())
    }

    /// Whether this library can be synced by this build; `Some(reason)` if not.
    fn sync_unsyncable(&self) -> Option<String> {
        let attachments = self.repo.load_attachments().ok()?;
        unsyncable(&attachments)
    }

    // ---- the engine's lifetime ----

    /// Start the engine the first time the page asks for it, and remember why if
    /// it must not be started. Idempotent.
    pub(crate) fn sync_ensure(&mut self) -> Result<(), String> {
        self.sync_ensure_identity()?;
        if self.sync.is_none() {
            self.sync = Some(Sync::start(self));
        }
        Ok(())
    }

    // ---- the view ----

    /// The 同步 page's state. Cheap: it reads the in-memory settings map and the
    /// peers it holds, and does no IO.
    pub(crate) fn sync_view(&self) -> SyncView {
        let me = self.sync_self_info();
        let now = quire_core::services::sync::engine::now_unix();
        let peers = self.sync_peers();

        let mut rows = vec![SyncRow {
            id: me.id.clone(),
            name: me.name.clone(),
            kind: me.kind.clone(),
            address: format!("{}:{}", local_ip().unwrap_or_default(), me.port),
            paired: true,
            online: true,
            last_sync: String::new(),
            self_device: true,
        }];
        // **Online first**, then the order the peer book holds them — which is the
        // order they were first heard. It used to be "paired first": a device that
        // had announced sat below one that had not, which with pairing gone meant
        // the device actually on the network was the second one on the list.
        let mut ordered: Vec<&PeerRecord> = peers.iter().filter(|p| p.id != me.id).collect();
        ordered.sort_by_key(|p| now.saturating_sub(p.last_seen) >= RECENT_ENOUGH_SECS);
        for p in ordered {
            rows.push(SyncRow {
                id: p.id.clone(),
                name: p.name.clone(),
                kind: p.kind.clone(),
                address: if p.ip.is_empty() {
                    String::new()
                } else {
                    format!("{}:{}", p.ip, p.port)
                },
                paired: p.paired,
                online: p.last_seen > 0 && now.saturating_sub(p.last_seen) < 15,
                last_sync: p.last_sync.clone(),
                self_device: false,
            });
        }

        let log = self
            .sync_log()
            .into_iter()
            .map(|l| SyncLogRow {
                at: l.at,
                peer: l.peer,
                ok: l.ok,
                message: l.message,
            })
            .collect();

        let (running, unsyncable, busy, status) = match &self.sync {
            Some(s) => (s.running(), s.unsyncable.clone(), s.busy, s.status.clone()),
            None => (false, None, false, String::new()),
        };

        SyncView {
            running,
            unsyncable,
            self_id: me.id,
            self_name: me.name,
            self_address: rows[0].address.clone(),
            auto: self.sync_auto(),
            interval: self.sync_interval(),
            port: SYNC_PORT,
            discovery_port: DISCOVERY_PORT,
            busy,
            status,
            rows,
            log,
        }
    }

    // ---- the commands the page sends ----

    fn sync_send(&mut self, cmd: Cmd) -> Result<(), String> {
        let sync = self.sync.as_ref().ok_or("同步还没启动")?;
        sync.cmds.send(cmd).map_err(|_| "同步引擎已经不在了".to_string())
    }

    pub(crate) fn sync_now(&mut self, peer_id: &str) -> Result<(), String> {
        // Any heard device (ADR-0029): the row exists because it announced, so
        // there is nothing left to ask before dialling it.
        let peer = self
            .sync_peers()
            .into_iter()
            .find(|p| p.id == peer_id)
            .ok_or_else(|| format!("没有这个设备: {peer_id}"))?;
        self.sync.as_mut().map(|s| s.busy = true);
        self.sync_send(Cmd::SyncWith(peer))
    }

    /// A round with every device still on the network — the 刷新 button in
    /// 收件箱 and 任务.
    ///
    /// The same list the auto-sync timer dials, on purpose: a hand-started round
    /// and a background one that disagree about who is reachable is how a user
    /// presses 刷新 and watches one of their devices quietly sit it out. Three
    /// things have to hold for a peer to be a target, and each is a way a round
    /// would otherwise fail rather than be skipped:
    ///
    /// * not this device — a record is its own peer in some tables, and dialling
    ///   yourself is a round that times out.
    /// * an address — nothing to dial without one; the device announces again in
    ///   4 s and the next round finds it.
    /// * announcing recently — one that stopped announcing has stopped listening
    ///   too, and `RECENT_ENOUGH_SECS` is the same window the page draws as 在线.
    ///
    /// There is no "paired" filter, and that is ADR-0029 rather than an omission:
    /// a row only exists here at all because its announcement was heard, so
    /// asking again whether it is trusted asks the same question twice.
    ///
    /// Returns the targets and, separately, the peers that were left out by
    /// name. The timer drops those silently: nobody is waiting on a tick. The
    /// button cannot — the press asked for *this* round, and a round that
    /// quietly skipped a device is the one that looks like it worked.
    pub(crate) fn peers_due_for_a_round(&self) -> (Vec<PeerRecord>, Vec<String>) {
        let now = quire_core::services::sync::engine::now_unix();
        let me = self.sync_self_info().id;
        let mut targets = Vec::new();
        let mut absent = Vec::new();
        for peer in self.sync_peers() {
            let reachable = peer.id != me
                && !peer.ip.is_empty()
                && now.saturating_sub(peer.last_seen) < RECENT_ENOUGH_SECS;
            if reachable {
                targets.push(peer);
            } else {
                absent.push(if peer.name.is_empty() { peer.id } else { peer.name });
            }
        }
        (targets, absent)
    }

    pub(crate) fn sync_now_all(&mut self) -> Result<(), String> {
        self.sync_ensure()?;
        let (targets, absent) = self.peers_due_for_a_round();
        if targets.is_empty() {
            return Err(if absent.is_empty() {
                "还没有别的设备出现在这个网络上 — 打开另一台设备就会自动同步。"
                    .to_string()
            } else {
                format!("{} 不在本网络上 — 它重新广播后就会自动同步。", absent.join("、"))
            });
        }
        self.sync.as_mut().map(|s| s.busy = true);
        let names: Vec<String> = targets
            .iter()
            .map(|p| if p.name.is_empty() { p.id.clone() } else { p.name.clone() })
            .collect();
        for p in targets {
            self.sync_send(Cmd::SyncWith(p))?;
        }
        if let Some(s) = self.sync.as_mut() {
            s.status = if absent.is_empty() {
                format!("正在与 {} 同步…", names.join("、"))
            } else {
                format!(
                    "正在与 {} 同步…（{} 不在本网络上，已跳过）",
                    names.join("、"),
                    absent.join("、")
                )
            };
        }
        Ok(())
    }

    /// Pair with a device that was discovered but has not been paired yet.
    pub(crate) fn sync_pair(&mut self, peer_id: &str) -> Result<(), String> {
        let peer = self
            .sync_peers()
            .into_iter()
            .find(|p| p.id == peer_id)
            .ok_or_else(|| format!("没有这个设备: {peer_id}"))?;
        if peer.ip.is_empty() {
            return Err("这个设备还没有地址 —— 等它下一次广播再试".to_string());
        }
        self.sync_send(Cmd::PairWith(peer))
    }

    pub(crate) fn sync_forget_peer(&mut self, peer_id: &str) -> Result<(), String> {
        self.sync_forget(peer_id)
    }

    // ---- the pump ----

    /// Answer whatever the engine's threads have queued, and fire the auto-sync
    /// timer. Returns whether anything happened, so the caller can decide
    /// whether the reply needs to carry a fresh view.
    ///
    /// The jobs are drained into a `Vec` first and handled afterwards, because
    /// handling one needs `&mut self` and a job held across that borrow would
    /// not compile. The jobs are the *shell's* work: the engine blocks its own
    /// threads until these are answered.
    pub(crate) fn sync_pump(&mut self) -> bool {
        let Some(sync) = self.sync.as_ref() else {
            return false;
        };
        let mut jobs = Vec::new();
        loop {
            match sync.jobs.try_recv() {
                Ok(job) => jobs.push(job),
                Err(TryRecvError::Empty) => break,
                // The engine is gone. Nothing left to answer.
                Err(TryRecvError::Disconnected) => break,
            }
        }

        let mut changed = !jobs.is_empty();
        for job in jobs {
            match job {
                Job::ExportSnapshot { reply } => {
                    match self.sync_export() {
                        Ok(snap) => {
                            let _ = reply.send(snap);
                        }
                        Err(e) => {
                            // Dropping the sender is the loud answer: the peer's
                            // pull fails instead of being handed a snapshot that
                            // would read as "this device deleted everything".
                            self.sync_log_push("", false, &e).ok();
                        }
                    }
                }
                Job::ListLocalAttachments { reply } => {
                    // This build carries no attachments (the gate), so the honest
                    // answer is none and the peer fetches nothing.
                    let _ = reply.send(Vec::new());
                }
                Job::AttachmentBytes { reply, .. } => {
                    let _ = reply.send(Vec::new());
                }
                Job::ApplyRemote {
                    peer,
                    snapshot,
                    reply,
                    ..
                } => {
                    // **No pairing gate** (ADR-0029): a device that announced
                    // itself on this LAN is a device we sync with. The
                    // announcement already carries its id in cleartext every four
                    // seconds, so pairing was never a credential — it was a
                    // question asked twice. What replaces it is the announcement
                    // itself: `Job::Discovered` records the sender, and this branch
                    // is reached by a device already heard from. A push from an
                    // address we have *never* heard is still refused, which is the
                    // one distinction left standing. The desktop's pump asks the
                    // same one.
                    let inbound_push = peer.kind.is_empty();
                    let heard = self.sync_peers().iter().any(|p| p.id == peer.id);
                    if inbound_push && !heard {
                        let message = format!(
                            "{} 推送了快照，但它从未在网络上广播过 — 已拒绝",
                            if peer.name.is_empty() {
                                "未知设备"
                            } else {
                                &peer.name
                            }
                        );
                        self.sync_log_push(&peer.id, false, &message).ok();
                        let _ = reply.send(Err(message));
                        continue;
                    }
                    let result = self.sync_apply_remote(&snapshot, &peer);
                    if let Ok(merged) = &result {
                        self.sync_store_shadow(&peer.id, merged).ok();
                        self.sync_note_synced(&peer.id, true).ok();
                    }
                    let _ = reply.send(result);
                }
                Job::InboundPair { device, ip, reply } => {
                    // Trust on the first exchange, the engine's own rule: both
                    // sides end up in each other's peer book.
                    let _ = reply.send(true);
                    self.sync_note_device(
                        &device.id,
                        &device.name,
                        &device.kind,
                        &ip,
                        device.port,
                        Some(true),
                    )
                    .ok();
                    self.sync_log_push(&device.name, true, "已加入").ok();
                    changed = true;
                }
                Job::Discovered { device, ip } => {
                    // Hearing a device is the whole of admitting it (ADR-0029) —
                    // the same call the desktop's pump makes, so a device syncs
                    // with one shell or the other, never with only one of them.
                    self.sync_note_device(
                        &device.id,
                        &device.name,
                        &device.kind,
                        &ip,
                        device.port,
                        Some(true),
                    )
                    .ok();
                }
                Job::Paired { device, ip } => {
                    self.sync_note_device(
                        &device.id,
                        &device.name,
                        &device.kind,
                        &ip,
                        device.port,
                        Some(true),
                    )
                    .ok();
                    self.sync_log_push(&device.name, true, "已加入").ok();
                    changed = true;
                }
                Job::SyncDone { peer_id, ok, message } => {
                    if let Some(s) = self.sync.as_mut() {
                        s.busy = false;
                        s.status = if ok {
                            message.clone()
                        } else {
                            format!("同步失败：{message}")
                        };
                    }
                    self.sync_note_synced(&peer_id, ok).ok();
                    self.sync_log_push(&peer_id, ok, &message).ok();
                    changed = true;
                }
                // The engine's own clock. This shell's interval lives in a
                // setting the user chose, so the timer is run below instead.
                Job::AutoTick => {}
            }
        }

        // The auto-sync timer: on, idle, and the interval has passed.
        let auto = self.sync_auto();
        let interval = self.sync_interval();
        let now = quire_core::services::sync::engine::now_unix();
        let due = match self.sync.as_ref() {
            Some(s) => auto && !s.busy && s.running() && now.saturating_sub(s.last_auto) >= interval,
            None => false,
        };
        if due {
            if let Some(s) = self.sync.as_mut() {
                s.last_auto = now;
            }
            let (peers, _absent) = self.peers_due_for_a_round();
            if !peers.is_empty() {
                if let Some(s) = self.sync.as_mut() {
                    s.busy = true;
                }
                for p in peers {
                    self.sync_send(Cmd::SyncWith(p)).ok();
                }
            }
        }
        changed
    }

    // ---- export ----

    /// The whole workspace as the wire sees it.
    ///
    /// The write queue is flushed first: the last few seconds of typing live in
    /// the persistence queue, not in the file, and a snapshot that missed them
    /// would be a snapshot the peer merges as "you changed nothing".
    pub(crate) fn sync_export(&mut self) -> Result<SyncSnapshot, String> {
        self.persistence.force_flush().map_err(|e| e.to_string())?;
        let state = self.repo.load().map_err(|e| e.to_string())?;
        let attachments = self.repo.load_attachments().map_err(|e| e.to_string())?;
        if let Some(why) = unsyncable(&attachments) {
            return Err(why);
        }

        let me = self.sync_self_info();
        let mut snap = SyncSnapshot::default();
        snap.device_id = me.id;
        snap.device = me.name;
        snap.pages = state.pages.iter().map(SPage::from).collect();
        snap.blocks = state.blocks.iter().map(SBlock::from).collect();
        snap.attachments = attachments.iter().map(SAttachment::from).collect();
        // Empty by the gate — and *correctly* empty, which is the whole point of
        // refusing a library that has any: the merge must never see this device
        // claim a database row it did not carry.
        snap.databases = Vec::new();

        // The organizer is read from memory: it is loaded whole at startup and
        // folded on every write, so the catalog is the most current copy there is.
        let catalog = self.org.catalog();
        snap.notes = catalog.notes.iter().map(SNote::from).collect();
        snap.tasks = catalog.tasks.iter().map(STask::from).collect();
        snap.lists = catalog.lists.iter().map(STaskList::from).collect();
        Ok(snap)
    }

    // ---- apply ----

    /// Merge a peer's snapshot in, three ways against the shadow this peer last
    /// agreed, and write the result. Answers the merged snapshot so the caller
    /// can push it back and store it as the peer's shadow.
    pub(crate) fn sync_apply_remote(
        &mut self,
        remote: &SyncSnapshot,
        peer: &PeerRecord,
    ) -> Result<SyncSnapshot, String> {
        // A desktop library with a database or a picture in it is the ordinary
        // case, not a broken peer: refusing the whole round over it was this
        // side's own doing, because `replace_all` below keeps only what it can
        // write — so `databases` and `attachments` are dropped from the inbound
        // snapshot and the rest of the library merges. Dropping them here is what
        // keeps them out of the shadow too, which is the point: a shadow that
        // claimed a database this store cannot hold would read, on the next
        // round, as "this device deleted it". Attachment rows would be worse —
        // `sync_unsyncable` refuses to export a library that carries any, so the
        // first accepted batch would switch this device's sync off for good.
        let mut remote = remote.clone();
        let carried = remote.databases.len() + remote.attachments.len();
        remote.databases.clear();
        remote.attachments.clear();

        let local = self.sync_export()?;
        let shadow = self.sync_shadow(&peer.id);
        let peer_name = if remote.device.is_empty() {
            peer.name.clone()
        } else {
            remote.device.clone()
        };

        // Say so in the log the sync page already draws: the round did run and
        // did move the pages, and a table the other device shows that this one
        // never grew is otherwise a silent difference between two libraries the
        // user was just told had synced.
        if carried > 0 {
            self.sync_log_push(
                &peer_name,
                true,
                &format!("其中 {carried} 项是数据库或附件 —— 本机不保存这些，没有同步进来"),
            )
            .ok();
        }

        let alloc = Allocators {
            page: Cell::new(seed(&local.pages, &remote.pages, |r| r.id)),
            block: Cell::new(seed(&local.blocks, &remote.blocks, |r| r.id)),
            note: Cell::new(seed(&local.notes, &remote.notes, |r| r.id)),
            task: Cell::new(seed(&local.tasks, &remote.tasks, |r| r.id)),
            list: Cell::new(seed(&local.lists, &remote.lists, |r| r.id)),
        };
        // The database layer's four allocators are constants: neither snapshot can
        // carry a database row by the time the merge sees them (this device answers
        // `databases = []`, an inbound one has them cleared above), so there is no
        // row to renumber. If that ever changes, these are the lines to fix.
        let mut next_attachment = || 1u64;
        let mut next_db = || 1u64;
        let mut next_property = || 1u64;
        let mut next_record = || 1u64;
        let mut next_view = || 1u64;
        let mut next_page = || {
            let v = alloc.page.get();
            alloc.page.set(v + 1);
            v
        };
        let mut next_block = || {
            let v = alloc.block.get();
            alloc.block.set(v + 1);
            v
        };
        let mut next_note = || {
            let v = alloc.note.get();
            alloc.note.set(v + 1);
            v
        };
        let mut next_task = || {
            let v = alloc.task.get();
            alloc.task.set(v + 1);
            v
        };
        let mut next_list = || {
            let v = alloc.list.get();
            alloc.list.set(v + 1);
            v
        };

        let mut new_uuid = || quire_core::core::organizer::new_uuid();

        let mut ctx = MergeCtx {
            next_page: &mut next_page,
            next_block: &mut next_block,
            next_attachment: &mut next_attachment,
            next_db: &mut next_db,
            next_property: &mut next_property,
            next_record: &mut next_record,
            next_view: &mut next_view,
            next_note: &mut next_note,
            next_task: &mut next_task,
            next_list: &mut next_list,
            new_uuid: &mut new_uuid,
        };
        let outcome = merge(&local, shadow.as_ref(), &remote, &peer_name, &mut ctx);
        let merged = outcome.merged;
        drop(ctx);

        // The merge's **conflicts** — both sides edited the same row, so the local
        // copy won and the peer's was dropped — are the one thing that happened in a
        // sync the page cannot otherwise explain: the row is not what the other
        // device shows, and nothing said why. They go into the log the page already
        // draws, which is this shell's version of the reference app's 冲突 list.
        for conflict in &outcome.conflicts {
            self.sync_log_push(&peer_name, true, &format!("冲突：{conflict}")).ok();
        }

        // The document half, as one `PersistedState`. `meta` and `settings` are
        // read out first and handed straight back: `replace_all` deletes those two
        // tables, and the peer book and this device's identity live in them.
        self.persistence.force_flush().map_err(|e| e.to_string())?;
        let mut base = self.repo.load().map_err(|e| e.to_string())?;
        base.pages = merged.pages.iter().map(SPage::to_core).collect();
        base.blocks = merged.blocks.iter().map(SBlock::to_core).collect();
        self.repo.replace_all(&base).map_err(|e| e.to_string())?;

        // The organizer is not part of a `PersistedState` — a bulk replace leaves
        // it alone on purpose — so its rows go back one `Change` at a time.
        let changes = organizer_changes(self.org.catalog(), &merged);
        if !changes.is_empty() {
            self.repo.apply(&changes).map_err(|e| e.to_string())?;
        }

        // Rebuild what is in memory from what is now in the file, and drop the
        // undo stacks: their entries name rows the merge may have replaced.
        self.reload_in_memory()?;

        // The merged snapshot becomes **this** device's when it is pushed back: the
        // peer keys its shadow by the sender's identity, and the sender of that push
        // is us.
        //
        // Without this the body still names whoever sent *us* the snapshot — the
        // desktop's id, because `merge` carries the remote's identity forward — so
        // the desktop receives a push from *itself*, does not find that id in its own
        // peer book, and answers 409. The pull half of the round has already landed by
        // then, so the data converges and the only visible symptom is a failed round
        // on the 同步 page; the desktop's own apply restamps for exactly this reason.
        let me = self.sync_self_info();
        let mut merged = merged;
        merged.device_id = me.id;
        merged.device = me.name;
        Ok(merged)
    }
}

/// The organizer's rows as the `Change`s that make the file match `merged`.
///
/// A row-level diff rather than a replace, because the organizer has no bulk
/// path: adds, updates and deletes against the catalog the session holds. Lists
/// go last so a task that moved out of a deleted list is written first.
fn organizer_changes(local: &OrganizerCatalog, merged: &SyncSnapshot) -> Vec<Change> {
    let mut out = Vec::new();

    for row in &merged.notes {
        let core = row.to_core();
        match local.note(core.id) {
            Some(before) if before == &core => {}
            Some(_) => out.push(Change::NoteUpdated(core)),
            None => out.push(Change::NoteAdded(core)),
        }
    }
    for row in &local.notes {
        if !merged.notes.iter().any(|r| r.id == row.id.0) {
            out.push(Change::NoteDeleted { id: row.id });
        }
    }

    for row in &merged.tasks {
        let core = row.to_core();
        match local.task(core.id) {
            Some(before) if before == &core => {}
            Some(_) => out.push(Change::TaskUpdated(core)),
            None => out.push(Change::TaskAdded(core)),
        }
    }
    for row in &local.tasks {
        if !merged.tasks.iter().any(|r| r.id == row.id.0) {
            out.push(Change::TaskDeleted {
                id: TaskId(row.id.0),
            });
        }
    }

    for row in &merged.lists {
        let core = row.to_core();
        match local.list(core.id) {
            Some(before) if before == &core => {}
            Some(_) => out.push(Change::TaskListUpdated(core)),
            None => out.push(Change::TaskListAdded(core)),
        }
    }
    for row in &local.lists {
        if !merged.lists.iter().any(|r| r.id == row.id.0) {
            out.push(Change::TaskListDeleted {
                id: ListId(row.id.0),
            });
        }
    }

    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use quire_core::services::sync::model::SDatabase;
    use quire_core::core::{Attachment, AttachmentId};

    /// A fresh library in its own directory. No `testing` helper here: the core's
    /// scratch type is behind its own feature, and a temp directory is enough for
    /// a test that opens and throws it away.
    fn scratch(tag: &str) -> std::path::PathBuf {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let dir = std::env::temp_dir().join(format!("quire-sync-{tag}-{nanos}"));
        std::fs::create_dir_all(&dir).expect("scratch dir");
        dir
    }

    /// The gate, which is the one thing that keeps a merge from reading absence as
    /// a deletion. Pure, so it costs nothing to pin.
    #[test]
    fn a_library_this_build_cannot_carry_is_refused_rather_than_half_synced() {
        assert!(unsyncable(&[]).is_none(), "an ordinary library syncs");

        let attachment = Attachment {
            id: AttachmentId(1),
            name: "a.png".into(),
            file: "1.png".into(),
            thumb: "1.cache.png".into(),
            mime: "image/png".into(),
            bytes: 1,
            width: 1,
            height: 1,
        };
        assert!(
            unsyncable(&[attachment]).is_some(),
            "an attachment row stops the sync: this device cannot send its bytes"
        );
    }

    /// What left the gate, and why it is worth a test of its own: a database
    /// *block* is not a database row. It arrives with the page it sits on, the way
    /// every other block does, and the old gate read it as the table itself — so one
    /// round with a desktop that had a table anywhere in its library was enough to
    /// answer `Some`, which is the answer that keeps the engine from starting at all:
    /// no server, no announcements, no pairing, and a line telling the user to sync
    /// with the desktop they had just synced with. The rows were never in that
    /// round's danger, since this build exports none and drops inbound ones.
    #[test]
    fn a_peers_table_does_not_switch_this_devices_sync_off() {
        let dir = scratch("table-block");
        let mut session = Session::open(dir.to_str().unwrap()).expect("open");
        session.dispatch(r#"{"op":"createPage","title":"带表格的页面"}"#);
        session.dispatch(r#"{"op":"appendBlock","kind":"database","text":""}"#);

        assert!(
            session.sync_unsyncable().is_none(),
            "the 同步 page still offers a round"
        );
        let snap = session
            .sync_export()
            .expect("a library with a table block exports");
        assert!(
            snap.blocks.iter().any(|b| b.kind == "database"),
            "the block itself travels, as part of its page"
        );
        assert!(
            snap.databases.is_empty(),
            "while the table's rows stay where they live"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// **A device in the peer book is a round's target, whatever `paired` says.**
    ///
    /// This is the shape of ADR-0029 stated as a test, at the one place it can be
    /// reached without a socket: the row is written the way the engine writes it —
    /// `sync_note_device` with `paired: true`, which is what `Job::Discovered`
    /// does — and the rule is asked who it would dial. The old rule filtered on
    /// `paired`, so the second half of this test (a row with the flag cleared,
    /// which is what a peer book written by a *pre-ADR-0029* build contains) is the
    /// case that used to be skipped and would have been left to the timeout sweep.
    ///
    /// It matters for the upgrade path rather than for a new install: a device
    /// that paired months ago and has a stale `paired: false` row — a device that
    /// was only ever discovered — would otherwise be invisible forever, because
    /// nothing rewrites that flag any more.
    #[test]
    fn every_device_in_the_peer_book_is_a_rounds_candidate() {
        let dir = scratch("target-rule");
        let mut session = Session::open(dir.to_str().unwrap()).expect("open");
        let now = quire_core::services::sync::engine::now_unix();

        // The row the announcement produces.
        session
            .sync_note_device("dev-heard", "Tablet", "android", "192.168.1.8", 5878, Some(true))
            .expect("record the heard device");
        // A row from a peer book an older build wrote: heard, but never paired.
        session
            .sync_note_device("dev-stale", "Phone", "android", "192.168.1.9", 5878, Some(false))
            .expect("record the stale row");
        // …and one that has left the building.
        session
            .sync_note_device("dev-gone", "Laptop", "windows", "192.168.1.7", 5878, Some(true))
            .expect("record the departed device");
        let mut peers = session.sync_peers();
        for p in peers.iter_mut() {
            if p.id == "dev-gone" {
                p.last_seen = now.saturating_sub(RECENT_ENOUGH_SECS + 60);
            }
        }
        session.sync_set_peers(&peers).expect("write the book back");

        let (targets, absent) = session.peers_due_for_a_round();
        let ids: Vec<&str> = targets.iter().map(|p| p.id.as_str()).collect();
        assert!(
            ids.contains(&"dev-heard"),
            "a device that just announced is dialled: {ids:?}"
        );
        assert!(
            ids.contains(&"dev-stale"),
            "a row an older build left unpaired is still a device, not a stranger: {ids:?}"
        );
        assert!(
            !ids.contains(&"dev-gone"),
            "a device that stopped announcing is not dialled — that is the timeout: {ids:?}"
        );
        assert_eq!(absent, vec!["Laptop".to_string()], "and it is named: {absent:?}");

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The shell's half of the protocol, end to end and without a socket: export
    /// the local workspace, merge in a peer's snapshot three ways against the
    /// shadow that peer last agreed to, write the result, and read it back out of
    /// the session.
    #[test]
    fn a_peers_change_lands_and_survives_the_write() {
        let dir = scratch("apply");
        let mut session = Session::open(dir.to_str().unwrap()).expect("open");

        // Something to carry: a page, a block, and a note.
        session.dispatch(r#"{"op":"createPage","title":"本地页面"}"#);
        session.dispatch(r#"{"op":"appendBlock","kind":"paragraph","text":"本地正文"}"#);
        session.dispatch(r#"{"op":"orgAddNote","body":"本地笔记"}"#);

        let local = session.sync_export().expect("export");
        assert!(!local.pages.is_empty(), "the page travels");
        assert_eq!(local.notes.len(), 1, "the note travels");
        assert!(local.databases.is_empty(), "and nothing else does");

        // The peer last agreed to exactly this, then renamed the page and added a
        // reply — so `shadow == local` and both changes are the peer's to land.
        session.sync_store_shadow("peer-1", &local).expect("shadow");
        let mut remote = local.clone();
        remote.device_id = "peer-1".into();
        remote.device = "Peer".into();
        for p in &mut remote.pages {
            p.title = "对端改过的标题".into();
        }
        remote.notes.push(SNote {
            id: 99,
            uuid: String::new(),
            title: String::new(),
            body: "对端加的评论".into(),
            pinned: false,
            tags: Vec::new(),
            created: 1,
            edited: 1,
            ref_note: Some(local.notes[0].id),
            deleted_at: None,
            // A row written by a real peer always carries one; the desktop's funnel
            // stamps it on the way into `exec`, and a fixture without one would be
            // describing a row no shell can produce.
            rev: quire_core::core::organizer::rev(1_700_000_000_000, "peer-1"),
        });

        let peer = PeerRecord {
            id: "peer-1".into(),
            name: "Peer".into(),
            kind: "windows".into(),
            ip: "127.0.0.1".into(),
            port: SYNC_PORT,
            paired: true,
            last_seen: 0,
            last_sync: String::new(),
        };
        let merged = session.sync_apply_remote(&remote, &peer).expect("apply");

        // What the merge answered, and what the session now holds, are the same
        // thing — which is what the peer is told and what the next export says.
        assert!(merged.pages.iter().any(|p| p.title == "对端改过的标题"));
        let reply = merged
            .notes
            .iter()
            .find(|n| n.body == "对端加的评论")
            .expect("the peer's reply landed");
        assert_eq!(reply.ref_note, Some(local.notes[0].id));
        assert_eq!(merged.notes.len(), 2);

        let back = session.sync_export().expect("re-export");
        assert!(back.pages.iter().any(|p| p.title == "对端改过的标题"));
        let reread = back
            .notes
            .iter()
            .find(|n| n.body == "对端加的评论")
            .expect("and it survived the write");
        // The reply came back a *comment*: a note carrying its ref, which is the
        // whole of SPEC §四十一's 引用 — so a sync moves comments as comments.
        assert_eq!(reread.ref_note, Some(local.notes[0].id));

        // The peer book and the shadows are `settings` rows, and `replace_all`
        // deletes that table — so this is the assertion that it was handed back
        // what it took. (The peer *book* is written by the pump as devices are
        // heard; `apply` only stores the shadow, so the identity row is the other
        // settings row worth checking here.)
        assert!(
            session.sync_shadow("peer-1").is_some(),
            "the shadow must survive the bulk replace"
        );
        assert!(
            !session.sync_self_info().id.is_empty(),
            "and so must this device's identity"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A desktop library with a database or a picture in it is the ordinary case,
    /// so the phone has to merge the pages it *can* carry instead of refusing the
    /// round — and must not let the dropped rows into the shadow it agrees to.
    ///
    /// The shadow is the whole point of the second half of this test: a shadow
    /// holding a database this store cannot write would read, on the next round,
    /// as "this device deleted it" — and an accepted attachment row is worse still,
    /// because `sync_unsyncable` refuses to export a library that carries any, so
    /// one friendly round would switch this device's sync off for good.
    #[test]
    fn an_inbound_database_or_attachment_is_dropped_rather_than_refusing_the_round() {
        let dir = scratch("strip");
        let mut session = Session::open(dir.to_str().unwrap()).expect("open");
        session.dispatch(r#"{"op":"createPage","title":"本机页面"}"#);

        let local = session.sync_export().expect("export");
        session.sync_store_shadow("peer-1", &local).expect("shadow");
        let mut remote = local.clone();
        remote.device_id = "peer-1".into();
        remote.device = "Desktop".into();
        for page in &mut remote.pages {
            page.title = "对端改过的标题".into();
        }
        remote.databases.push(SDatabase {
            id: 77,
            name: "任务表".into(),
            template: String::new(),
            properties: Vec::new(),
            views: Vec::new(),
            records: Vec::new(),
            values: Vec::new(),
        });
        remote.attachments.push(SAttachment {
            id: 88,
            name: "图片".into(),
            file: "88.png".into(),
            thumb: String::new(),
            mime: "image/png".into(),
            bytes: 4,
            width: 0,
            height: 0,
        });

        let peer = PeerRecord {
            id: "peer-1".into(),
            name: "Desktop".into(),
            kind: "windows".into(),
            ip: "127.0.0.1".into(),
            port: SYNC_PORT,
            paired: true,
            last_seen: 0,
            last_sync: String::new(),
        };
        let merged = session
            .sync_apply_remote(&remote, &peer)
            .expect("the round runs");

        assert!(
            merged.pages.iter().any(|p| p.title == "对端改过的标题"),
            "the pages still merged"
        );
        assert!(
            merged.databases.is_empty() && merged.attachments.is_empty(),
            "and the rows this store cannot hold were not claimed"
        );

        let shadow = session.sync_shadow("peer-1").expect("shadow stored");
        assert!(
            shadow.databases.is_empty() && shadow.attachments.is_empty(),
            "the shadow this device agrees to carries nothing it does not have"
        );

        assert!(
            session
                .sync_log()
                .iter()
                .any(|line| line.message.contains("数据库或附件")),
            "the strip is said out loud on the 同步 page"
        );

        // The round must not have cost this device its next one.
        let back = session.sync_export().expect("re-export");
        assert!(back.attachments.is_empty(), "no attachment landed");

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A conflict — both sides edited one row — is **said out loud**, whichever
    /// copy the revision settles on.
    ///
    /// The line is the only thing that can explain a row the other device shows
    /// differently: the merge picks a winner deterministically (core ADR-0004), so
    /// the two ends agree afterwards, but nothing else on the 同步 page would say
    /// that a write happened here and is no longer the one displayed. It is this
    /// shell's version of the reference app's 冲突 list. Here the local edit is the
    /// *later* one, so it is the copy that stands — which is the revision's answer,
    /// not a preference for the local side.
    #[test]
    fn a_merge_conflict_is_written_to_the_sync_log() {
        let dir = scratch("conflict-log");
        let mut session = Session::open(dir.to_str().unwrap()).expect("open");
        session.dispatch(r#"{"op":"orgAddNote","body":"原始"}"#);

        let local = session.sync_export().expect("export");
        assert_eq!(local.notes.len(), 1);
        session.sync_store_shadow("peer-1", &local).expect("shadow");

        // The peer changed the note's text…
        let mut remote = local.clone();
        remote.device_id = "peer-1".into();
        remote.device = "Peer".into();
        for note in &mut remote.notes {
            note.body = "对端改的".into();
        }
        // …and so did this device, after the shadow was taken: two concurrent
        // writes to one row are the shape the merge has to settle by the revision,
        // and this device's is the later one.
        let id = local.notes[0].id;
        session.dispatch(&format!(
            r#"{{"op":"orgNoteContent","note":{id},"body":"本机改的","tags":""}}"#
        ));

        let peer = PeerRecord {
            id: "peer-1".into(),
            name: "Peer".into(),
            kind: "windows".into(),
            ip: "127.0.0.1".into(),
            port: SYNC_PORT,
            paired: true,
            last_seen: 0,
            last_sync: String::new(),
        };
        let merged = session.sync_apply_remote(&remote, &peer).expect("apply");
        let body = merged.notes.iter().find(|n| n.id == id).expect("the note").body.clone();
        assert_eq!(body, "本机改的", "the local edit is the one that keeps");

        let logged: Vec<String> = session
            .sync_log()
            .into_iter()
            .filter(|l| l.message.starts_with("冲突："))
            .map(|l| l.message)
            .collect();
        assert_eq!(logged.len(), 1, "one conflict, one line: {logged:?}");
        assert!(logged[0].contains("note"), "and it names the row: {logged:?}");

        let _ = std::fs::remove_dir_all(&dir);
    }
}
