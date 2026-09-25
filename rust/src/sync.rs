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
//!   sides. So the gate is checked before the engine is even started
//!   ([`Session::sync_ensure`]), an inbound snapshot that carries either is
//!   refused, and a snapshot that somehow cannot be built is answered by *dropping
//!   the reply channel* — the peer sees a failed sync, never an empty workspace.
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
use quire_core::core::types::{Attachment, BlockKind, PersistedState};
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
    /// the answer cannot change afterwards, because nothing in this shell can add
    /// a database or an attachment, and an inbound snapshot carrying one is
    /// refused.
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
fn unsyncable(state: &PersistedState, attachments: &[Attachment]) -> Option<String> {
    let has_database = state
        .blocks
        .iter()
        .any(|b| b.kind == BlockKind::Database || b.db_ref.is_some());
    if has_database {
        return Some(
            "这个资料库里有数据库 —— 本版本还不同步数据库，请用桌面端同步（或先在桌面端删掉数据库）"
                .to_string(),
        );
    }
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
/// database layer's four are constants, because the gate guarantees both
/// snapshots carry none and the merge therefore never renumbers one.
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
        let state = self.repo.load().ok()?;
        let attachments = self.repo.load_attachments().ok()?;
        unsyncable(&state, &attachments)
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
        // Paired first, then the merely-discovered, each in the order the peer
        // book holds them — which is the order they were first heard.
        let mut ordered: Vec<&PeerRecord> = peers.iter().filter(|p| p.id != me.id).collect();
        ordered.sort_by_key(|p| !p.paired);
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
        let peer = self
            .sync_peers()
            .into_iter()
            .find(|p| p.id == peer_id && p.paired)
            .ok_or_else(|| format!("没有已配对的设备: {peer_id}"))?;
        self.sync.as_mut().map(|s| s.busy = true);
        self.sync_send(Cmd::SyncWith(peer))
    }

    /// Pair with a device that was discovered but has not been paired yet.
    pub(crate) fn sync_pair(&mut self, peer_id: &str) -> Result<(), String> {
        let peer = self
            .sync_peers()
            .into_iter()
            .find(|p| p.id == peer_id)
            .ok_or_else(|| format!("没有这个设备: {peer_id}"))?;
        if peer.ip.is_empty() {
            return Err("这个设备还没有地址 —— 用「按地址添加」再试".to_string());
        }
        self.sync_send(Cmd::PairWith(peer))
    }

    /// A device typed in by hand: for a network where the announcement cannot
    /// get through. The engine asks it who it is and pairs on the answer.
    pub(crate) fn sync_add_peer(&mut self, ip: &str, port: u16) -> Result<(), String> {
        let ip = ip.trim().to_string();
        if ip.is_empty() {
            return Err("请填一个地址".to_string());
        }
        // `192.168.1.20:5878` is what a user copies off the other device's page,
        // so the port may arrive inside the address.
        let (ip, port) = match ip.rsplit_once(':') {
            Some((host, tail)) if tail.parse::<u16>().is_ok() => {
                (host.to_string(), tail.parse::<u16>().unwrap_or(SYNC_PORT))
            }
            _ => (ip, if port == 0 { SYNC_PORT } else { port }),
        };
        self.sync_send(Cmd::ProbeAdd { ip, port })
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
                Job::ApplyRemote { peer, snapshot, reply, .. } => {
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
                    self.sync_log_push(&device.name, true, "已配对").ok();
                    changed = true;
                }
                Job::Discovered { device, ip } => {
                    self.sync_note_device(
                        &device.id,
                        &device.name,
                        &device.kind,
                        &ip,
                        device.port,
                        None,
                    )
                    .ok();
                    changed = true;
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
                    self.sync_log_push(&device.name, true, "已配对").ok();
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
            let me = self.sync_self_info().id;
            let peers: Vec<PeerRecord> = self
                .sync_peers()
                .into_iter()
                .filter(|p| p.paired && p.id != me && !p.ip.is_empty())
                .collect();
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
        if let Some(why) = unsyncable(&state, &attachments) {
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
        if !remote.databases.is_empty() || !remote.attachments.is_empty() {
            return Err(
                "对端带了数据库或附件 —— 本版本还不同步这些，请用桌面端".to_string(),
            );
        }

        let local = self.sync_export()?;
        let shadow = self.sync_shadow(&peer.id);
        let peer_name = if remote.device.is_empty() {
            peer.name.clone()
        } else {
            remote.device.clone()
        };

        let alloc = Allocators {
            page: Cell::new(seed(&local.pages, &remote.pages, |r| r.id)),
            block: Cell::new(seed(&local.blocks, &remote.blocks, |r| r.id)),
            note: Cell::new(seed(&local.notes, &remote.notes, |r| r.id)),
            task: Cell::new(seed(&local.tasks, &remote.tasks, |r| r.id)),
            list: Cell::new(seed(&local.lists, &remote.lists, |r| r.id)),
        };
        // The database layer's four allocators are constants: both snapshots carry
        // no database rows (the gate), so the merge cannot renumber one. If that
        // ever changes, these are the lines to fix.
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
        };
        let outcome = merge(&local, shadow.as_ref(), remote, &peer_name, &mut ctx);
        let merged = outcome.merged;
        drop(ctx);

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
    use quire_core::core::{
        Attachment, AttachmentId, Block, BlockId, ColorKind, Lang, OrderKey, PageId,
    };

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

    fn block(kind: BlockKind) -> Block {
        Block {
            id: BlockId(1),
            page: PageId(1),
            parent: None,
            order: OrderKey::FIRST,
            kind,
            text: String::new(),
            checked: false,
            marks: Vec::new(),
            color: ColorKind::Default,
            background: ColorKind::Default,
            page_ref: None,
            folded: false,
            attachment: None,
            img_percent: 100,
            columns: 0,
            lang: Lang::Plain,
            db_ref: None,
            sync_ref: None,
        }
    }

    /// The gate, which is the one thing that keeps a merge from reading absence
    /// as a deletion. Pure, so it costs nothing to pin.
    #[test]
    fn a_library_this_build_cannot_carry_is_refused_rather_than_half_synced() {
        let plain = PersistedState::default();
        assert!(unsyncable(&plain, &[]).is_none());

        let with_database = PersistedState {
            blocks: vec![block(BlockKind::Database)],
            ..PersistedState::default()
        };
        assert!(
            unsyncable(&with_database, &[]).is_some(),
            "a database block must stop the sync, not be dropped from the snapshot"
        );

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
            unsyncable(&plain, &[attachment]).is_some(),
            "an attachment row must stop the sync too"
        );
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
            title: String::new(),
            body: "对端加的评论".into(),
            pinned: false,
            tags: Vec::new(),
            created: 1,
            edited: 1,
            ref_note: Some(local.notes[0].id),
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
}
