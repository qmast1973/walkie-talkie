// 무전기(Walkie-Talkie) 서버
// - 대기실: 접속자 전체 명단 + 방 목록
// - 방: 방 인원만 들어갈 수 있는 채널(전체 공개 / 선택 인원 전용)
// - PTT: 채널마다 한 번에 한 사람만 송신(서버가 송신권을 관리)
const path = require('path');
const http = require('http');
const crypto = require('crypto');
const express = require('express');
const { Server } = require('socket.io');

const PORT = process.env.PORT || 3000;
const MAX_TALK_MS = 60_000; // 한 번에 최대 60초 송신
const MAX_CHANNELS = 12;
const MAX_ROOMS = 200;
const GRACE_MS = Number(process.env.GRACE_MS) || 45_000; // 방에 있던 사람: 연결이 끊겨도 이 시간 동안은 방·채널 자리를 유지
const LOBBY_GRACE_MS = Number(process.env.LOBBY_GRACE_MS) || 10_000; // 대기실에만 있던 사람: 금방 명단에서 정리
const ROOM_IDLE_MS = 24 * 60 * 60_000; // 아무도 없는 방은 24시간 뒤 자동 삭제(방장은 언제든 직접 삭제 가능)
const GHOST_MS = 24 * 60 * 60_000; // 대기 모드 등으로 끊긴 사람의 '방·채널 자리'를 기억하는 시간

// 지금 돌고 있는 코드 버전(커밋). Render는 RENDER_GIT_COMMIT을 넣어 줌
const VERSION = (() => {
  if (process.env.RENDER_GIT_COMMIT) return process.env.RENDER_GIT_COMMIT.slice(0, 7);
  try {
    return require('child_process').execSync('git rev-parse --short HEAD', { cwd: __dirname, stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
  } catch {
    return 'dev';
  }
})();

const app = express();
app.use(express.static(path.join(__dirname, 'public')));
app.get('/healthz', (_req, res) => res.send('ok'));
// 저장 방식(db/memory)과 방 개수도 함께 알려 줌 — 배포 후 DB 연결 여부 확인용
app.get('/version', (_req, res) => res.json({ version: VERSION, storage: pool ? 'db' : 'memory', rooms: rooms.size }));

const server = http.createServer(app);
// 끊긴 연결을 빨리 알아채도록 핑 간격을 짧게(기본 25초+20초 → 10초+10초)
const io = new Server(server, { maxHttpBufferSize: 64 * 1024, pingInterval: 10_000, pingTimeout: 10_000 });

/** @type {Map<string, {id:string,key:string,socketId:string|null,online:boolean,graceTimer:any,nick:string,roomId:string|null,channelId:string|null,color:number}>} */
const users = new Map();
/** 재접속용 비밀 키 → 공개 user id */
const keys = new Map();
/** 연결이 끊겨 명단에서 빠졌지만 돌아오면 원래 방·채널로 복귀시킬 사람: id → {id,key,nick,color,lastRoomId,lastChannelId,at} */
const ghosts = new Map();
/** @type {Map<string, {id:string,name:string,ownerId:string,passHash:string|null,channels:Map<string,any>,createdAt:number}>} */
const rooms = new Map();

const uid = (n = 6) => crypto.randomBytes(n).toString('base64url').slice(0, n + 2);
const hash = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');
const clean = (s, max) => String(s ?? '').replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, max);

function newChannel(name, createdBy, members = null, isDefault = false) {
  return {
    id: uid(4),
    name,
    createdBy,
    members, // null = 방 인원 전체, 배열 = 선택된 인원만
    isDefault,
    talker: null, // 현재 송신 중인 user id
    talkStarted: 0,
  };
}

function canUseChannel(room, ch, userId) {
  if (!room || !ch) return false;
  const u = users.get(userId);
  if (!u || u.roomId !== room.id) return false;
  if (ch.members === null) return true;
  return ch.members.includes(userId) || room.ownerId === userId || ch.createdBy === userId;
}

function roomMembers(room) {
  return [...users.values()].filter((u) => u.roomId === room.id);
}

function lobbyState() {
  return {
    users: [...users.values()].map((u) => ({
      id: u.id,
      nick: u.nick,
      color: u.color,
      roomId: u.roomId,
      online: u.online,
      roomName: u.roomId ? rooms.get(u.roomId)?.name ?? null : null,
    })),
    rooms: [...rooms.values()]
      .map((r) => ({
        id: r.id,
        name: r.name,
        locked: !!r.passHash,
        count: roomMembers(r).filter((m) => m.online).length,
        ownerId: r.ownerId,
        ownerNick: users.get(r.ownerId)?.nick ?? r.known.get(r.ownerId) ?? '',
        emptySince: r.emptySince,
        createdAt: r.createdAt,
      }))
      .sort((a, b) => a.createdAt - b.createdAt),
  };
}

function roomStateFor(room, viewerId) {
  return {
    id: room.id,
    name: room.name,
    ownerId: room.ownerId,
    locked: !!room.passHash,
    members: roomMembers(room).map((u) => ({ id: u.id, nick: u.nick, color: u.color, channelId: u.channelId, online: u.online })),
    // 이 방에 들어왔던 적이 있는 사람 전체(대기 모드로 빠진 사람 포함) — 선택 인원 채널 지정에 사용
    known: [...room.known].map(([id, nick]) => {
      const live = users.get(id);
      return { id, nick: live?.nick ?? nick, color: live?.color ?? ghosts.get(id)?.color ?? 0, here: live?.roomId === room.id };
    }),
    channels: [...room.channels.values()].map((c) => ({
      id: c.id,
      name: c.name,
      createdBy: c.createdBy,
      isDefault: c.isDefault,
      members: c.members,
      talker: c.talker,
      allowed: canUseChannel(room, c, viewerId),
    })),
  };
}

let lobbyTimer = null;
function broadcastLobby() {
  if (lobbyTimer) return;
  lobbyTimer = setTimeout(() => {
    lobbyTimer = null;
    io.emit('lobby', lobbyState());
  }, 50);
}

function broadcastRoom(room) {
  for (const u of roomMembers(room)) {
    io.to('u:' + u.id).emit('room', roomStateFor(room, u.id));
  }
}

function releaseTalk(room, ch, reason = 'end') {
  if (!ch || !ch.talker) return;
  const talker = ch.talker;
  ch.talker = null;
  ch.talkStarted = 0;
  io.to(`ch:${ch.id}`).emit('talk:end', { channelId: ch.id, userId: talker, reason });
  broadcastRoom(room);
}

function leaveChannel(u) {
  if (!u.roomId || !u.channelId) return;
  const room = rooms.get(u.roomId);
  const ch = room?.channels.get(u.channelId);
  if (u.socketId) io.sockets.sockets.get(u.socketId)?.leave(`ch:${u.channelId}`);
  if (ch && ch.talker === u.id) releaseTalk(room, ch, 'left');
  u.channelId = null;
}

function joinChannel(u, room, ch) {
  leaveChannel(u);
  u.channelId = ch.id;
  if (u.socketId) io.sockets.sockets.get(u.socketId)?.join(`ch:${ch.id}`);
}

function leaveRoom(u) {
  if (!u.roomId) return;
  const room = rooms.get(u.roomId);
  leaveChannel(u);
  u.roomId = null;
  if (!room) return;
  // 방은 비어도 지우지 않음(24시간 뒤 자동 정리). 채널과 선택 인원 권한도 그대로 유지
  if (roomMembers(room).length === 0) room.emptySince = Date.now();
  broadcastRoom(room);
}

function enterRoom(u, room, preferChannelId) {
  if (u.roomId !== room.id) leaveRoom(u);
  u.roomId = room.id;
  room.known.set(u.id, u.nick);
  room.emptySince = null;
  const pref = preferChannelId && room.channels.get(preferChannelId);
  const main = [...room.channels.values()].find((c) => c.isDefault);
  joinChannel(u, room, pref && canUseChannel(room, pref, u.id) ? pref : main);
}

function deleteRoom(room, reason) {
  for (const m of roomMembers(room)) {
    leaveChannel(m);
    m.roomId = null;
    io.to('u:' + m.id).emit('room:deleted', { roomId: room.id, reason });
  }
  rooms.delete(room.id);
  broadcastLobby();
}

// 빈 방·오래된 자리 정리(1분마다)
setInterval(() => {
  const now = Date.now();
  for (const room of rooms.values()) {
    if (room.emptySince && now - room.emptySince > ROOM_IDLE_MS) deleteRoom(room, 'idle');
  }
  for (const g of ghosts.values()) {
    if (now - g.at > GHOST_MS) { ghosts.delete(g.id); keys.delete(g.key); }
  }
}, 60_000);

// 송신 시간 제한 감시
setInterval(() => {
  const now = Date.now();
  for (const room of rooms.values()) {
    for (const ch of room.channels.values()) {
      if (ch.talker && now - ch.talkStarted > MAX_TALK_MS) {
        io.to('u:' + ch.talker).emit('toast', '송신 시간(60초)이 지나 자동으로 끊었어요.');
        releaseTalk(room, ch, 'timeout');
      }
    }
  }
}, 1000);

io.on('connection', (socket) => {
  const ack = (cb, payload) => typeof cb === 'function' && cb(payload);

  socket.on('hello', ({ nick, key } = {}, cb) => {
    nick = clean(nick, 16);
    if (!nick) return ack(cb, { ok: false, error: '닉네임을 입력해 주세요.' });
    key = typeof key === 'string' && /^[A-Za-z0-9_-]{16,64}$/.test(key) ? key : null;
    const nickKey = nick.normalize('NFC').toLowerCase().replace(/\s+/g, '');
    let u = key && users.get(keys.get(key));
    let ghost = !u && key ? ghosts.get(keys.get(key)) : null;
    const sameNick = [...users.values()].find((x) => x !== u && x.nick.normalize('NFC').toLowerCase().replace(/\s+/g, '') === nickKey);
    if (sameNick) {
      if (sameNick.online) {
        return ack(cb, { ok: false, nickTaken: true, error: `‘${sameNick.nick}’은(는) 지금 다른 사람이 쓰고 있어요. 다른 닉네임을 정해 주세요.` });
      }
      // 연결이 끊긴 채 자리만 남아 있는 같은 닉네임 → 새로 만들지 않고 그 자리를 이어받음
      if (!u || !u.roomId) {
        if (u) { users.delete(u.id); keys.delete(u.key); clearTimeout(u.graceTimer); if (u.socketId && u.socketId !== socket.id) io.sockets.sockets.get(u.socketId)?.disconnect(true); }
        u = sameNick;
        ghost = null;
      } else {
        return ack(cb, { ok: false, nickTaken: true, error: `‘${sameNick.nick}’은(는) 이미 쓰이고 있어요. 다른 닉네임을 정해 주세요.` });
      }
    }
    // 같은 기기(같은 키)로 다른 창이 접속 중이면 이전 창을 끊고 이 창으로 넘김
    if (u && u.online && u.socketId && u.socketId !== socket.id) {
      const old = io.sockets.sockets.get(u.socketId);
      if (old) { old.emit('replaced'); old.data.uid = null; old.disconnect(true); }
    }
    if (u) {
      clearTimeout(u.graceTimer);
      u.graceTimer = null;
    } else if (ghost) {
      // 대기 모드 등으로 오래 끊겼다 돌아온 사람: 같은 id로 되살림(선택 인원 채널 권한 유지)
      ghosts.delete(ghost.id);
      key = ghost.key;
      u = { id: ghost.id, key, socketId: null, online: false, graceTimer: null, nick, roomId: null, channelId: null, color: ghost.color };
      users.set(u.id, u);
    } else {
      key = uid(24);
      const id = uid(8);
      u = { id, key, socketId: null, online: false, graceTimer: null, nick, roomId: null, channelId: null, color: parseInt(hash(id).slice(0, 4), 16) % 360 };
      users.set(id, u);
      keys.set(key, id);
    }
    u.nick = nick;
    u.socketId = socket.id;
    u.online = true;
    socket.data.uid = u.id;
    socket.join('u:' + u.id);
    if (ghost && ghost.lastRoomId && rooms.has(ghost.lastRoomId)) enterRoom(u, rooms.get(ghost.lastRoomId), ghost.lastChannelId);
    const room = u.roomId && rooms.get(u.roomId);
    if (room && u.channelId) socket.join(`ch:${u.channelId}`);
    if (room) room.known.set(u.id, u.nick);
    if (room) broadcastRoom(room);
    broadcastLobby();
    ack(cb, { ok: true, version: VERSION, key: u.key, me: { id: u.id, nick: u.nick, color: u.color }, lobby: lobbyState(), room: room ? roomStateFor(room, u.id) : null });
  });

  const me = () => (socket.data.uid ? users.get(socket.data.uid) : undefined);

  socket.on('room:create', ({ name, password } = {}, cb) => {
    const u = me();
    if (!u) return ack(cb, { ok: false, error: '먼저 닉네임을 정해 주세요.' });
    name = clean(name, 24);
    if (!name) return ack(cb, { ok: false, error: '방 이름을 입력해 주세요.' });
    if (rooms.size >= MAX_ROOMS) return ack(cb, { ok: false, error: '방이 너무 많아요. 잠시 후 다시 시도해 주세요.' });
    leaveRoom(u);
    const room = {
      id: uid(5),
      name,
      ownerId: u.id,
      passHash: password ? hash(clean(password, 32)) : null,
      channels: new Map(),
      known: new Map(), // id → nick
      emptySince: null,
      createdAt: Date.now(),
    };
    const main = newChannel('공용', u.id, null, true);
    room.channels.set(main.id, main);
    rooms.set(room.id, room);
    enterRoom(u, room);
    broadcastRoom(room);
    broadcastLobby();
    ack(cb, { ok: true, room: roomStateFor(room, u.id) });
  });

  socket.on('room:join', ({ roomId, password } = {}, cb) => {
    const u = me();
    const room = rooms.get(roomId);
    if (!u) return ack(cb, { ok: false, error: '먼저 닉네임을 정해 주세요.' });
    if (!room) return ack(cb, { ok: false, error: '방이 없어졌어요. 목록을 확인해 주세요.' });
    if (room.passHash && hash(clean(password, 32)) !== room.passHash) {
      return ack(cb, { ok: false, error: '비밀번호가 맞지 않아요.', needPassword: true });
    }
    enterRoom(u, room);
    broadcastRoom(room);
    broadcastLobby();
    ack(cb, { ok: true, room: roomStateFor(room, u.id) });
  });

  socket.on('room:delete', ({ roomId } = {}, cb) => {
    const u = me();
    const room = rooms.get(roomId);
    if (!u || !room) return ack(cb, { ok: false, error: '방을 찾을 수 없어요.' });
    if (room.ownerId !== u.id) return ack(cb, { ok: false, error: '방장만 방을 삭제할 수 있어요.' });
    deleteRoom(room, 'owner');
    ack(cb, { ok: true });
  });

  socket.on('room:leave', (_p, cb) => {
    const u = me();
    if (u) leaveRoom(u);
    broadcastLobby();
    ack(cb, { ok: true });
  });

  socket.on('channel:create', ({ name, members } = {}, cb) => {
    const u = me();
    const room = u && rooms.get(u.roomId);
    if (!room) return ack(cb, { ok: false, error: '방에 들어가 있어야 채널을 만들 수 있어요.' });
    name = clean(name, 16);
    if (!name) return ack(cb, { ok: false, error: '채널 이름을 입력해 주세요.' });
    if (room.channels.size >= MAX_CHANNELS) return ack(cb, { ok: false, error: `채널은 방마다 ${MAX_CHANNELS}개까지 만들 수 있어요.` });
    let list = null;
    if (Array.isArray(members)) {
      list = [...new Set([u.id, ...members.filter((id) => room.known.has(id))])];
    }
    const ch = newChannel(name, u.id, list);
    room.channels.set(ch.id, ch);
    joinChannel(u, room, ch);
    broadcastRoom(room);
    ack(cb, { ok: true, channelId: ch.id });
  });

  socket.on('channel:update', ({ channelId, name, members } = {}, cb) => {
    const u = me();
    const room = u && rooms.get(u.roomId);
    const ch = room?.channels.get(channelId);
    if (!ch) return ack(cb, { ok: false, error: '채널을 찾을 수 없어요.' });
    if (ch.createdBy !== u.id && room.ownerId !== u.id) return ack(cb, { ok: false, error: '채널을 만든 사람이나 방장만 바꿀 수 있어요.' });
    if (name !== undefined) {
      const n = clean(name, 16);
      if (n) ch.name = n;
    }
    if (!ch.isDefault && members !== undefined) {
      if (members === null) ch.members = null;
      else if (Array.isArray(members)) {
        ch.members = [...new Set([ch.createdBy, ...members.filter((id) => room.known.has(id))])];
      }
      // 권한이 사라진 사람은 공용 채널로 이동
      const main = [...room.channels.values()].find((c) => c.isDefault);
      for (const m of roomMembers(room)) {
        if (m.channelId === ch.id && !canUseChannel(room, ch, m.id)) {
          joinChannel(m, room, main);
          io.to('u:' + m.id).emit('toast', `'${ch.name}' 채널 사용 권한이 바뀌어 공용 채널로 옮겼어요.`);
        }
      }
    }
    broadcastRoom(room);
    ack(cb, { ok: true });
  });

  socket.on('channel:delete', ({ channelId } = {}, cb) => {
    const u = me();
    const room = u && rooms.get(u.roomId);
    const ch = room?.channels.get(channelId);
    if (!ch) return ack(cb, { ok: false, error: '채널을 찾을 수 없어요.' });
    if (ch.isDefault) return ack(cb, { ok: false, error: '공용 채널은 지울 수 없어요.' });
    if (ch.createdBy !== u.id && room.ownerId !== u.id) return ack(cb, { ok: false, error: '채널을 만든 사람이나 방장만 지울 수 있어요.' });
    const main = [...room.channels.values()].find((c) => c.isDefault);
    releaseTalk(room, ch, 'deleted');
    for (const m of roomMembers(room)) if (m.channelId === ch.id) joinChannel(m, room, main);
    room.channels.delete(ch.id);
    broadcastRoom(room);
    ack(cb, { ok: true });
  });

  socket.on('channel:join', ({ channelId } = {}, cb) => {
    const u = me();
    const room = u && rooms.get(u.roomId);
    const ch = room?.channels.get(channelId);
    if (!ch) return ack(cb, { ok: false, error: '채널을 찾을 수 없어요.' });
    if (!canUseChannel(room, ch, u.id)) return ack(cb, { ok: false, error: '이 채널은 지정된 인원만 쓸 수 있어요.' });
    joinChannel(u, room, ch);
    broadcastRoom(room);
    ack(cb, { ok: true });
  });

  // ---- PTT ----
  socket.on('talk:start', (_p, cb) => {
    const u = me();
    const room = u && rooms.get(u.roomId);
    const ch = room?.channels.get(u?.channelId);
    if (!ch || !canUseChannel(room, ch, u.id)) return ack(cb, { ok: false, error: '채널에 들어가 있지 않아요.' });
    if (ch.talker && ch.talker !== u.id) {
      return ack(cb, { ok: false, busy: true, error: `${users.get(ch.talker)?.nick ?? '다른 사람'} 님이 송신 중이에요.` });
    }
    ch.talker = u.id;
    ch.talkStarted = Date.now();
    socket.to(`ch:${ch.id}`).emit('talk:start', { channelId: ch.id, userId: u.id, nick: u.nick });
    broadcastRoom(room);
    ack(cb, { ok: true });
  });

  socket.on('talk:stop', () => {
    const u = me();
    const room = u && rooms.get(u.roomId);
    const ch = room?.channels.get(u?.channelId);
    if (ch && ch.talker === u.id) releaseTalk(room, ch);
  });

  // 오디오 프레임: 16kHz mono Int16 PCM (ArrayBuffer)
  socket.on('audio', (buf) => {
    const u = me();
    if (!u || !u.channelId) return;
    const room = rooms.get(u.roomId);
    const ch = room?.channels.get(u.channelId);
    if (!ch || ch.talker !== u.id) return;
    if (!(buf instanceof Buffer) || buf.length > 16_000) return;
    socket.to(`ch:${ch.id}`).volatile.emit('audio', { from: u.id, data: buf });
  });

  socket.on('disconnect', () => {
    const u = me();
    if (!u || u.socketId !== socket.id) return;
    u.online = false;
    u.socketId = null;
    const room = u.roomId && rooms.get(u.roomId);
    const ch = room?.channels.get(u.channelId);
    if (ch && ch.talker === u.id) releaseTalk(room, ch, 'left');
    if (room) broadcastRoom(room);
    broadcastLobby();
    u.graceTimer = setTimeout(() => {
      // 명단에서는 빼되, 돌아오면 원래 방·채널로 복귀하도록 자리를 기억
      ghosts.set(u.id, { id: u.id, key: u.key, nick: u.nick, color: u.color, lastRoomId: u.roomId, lastChannelId: u.channelId, at: Date.now() });
      leaveRoom(u);
      users.delete(u.id);
      broadcastLobby();
    }, u.roomId ? GRACE_MS : LOBBY_GRACE_MS);
  });
});

// ---- 저장(선택) ----
// DATABASE_URL(Neon 등 Postgres 연결 주소)이 있으면 방·채널·접속자 자리를 DB에 저장하고,
// 서버가 재시작되면 다시 불러옴. 없으면 지금까지처럼 메모리에서만 동작.
let pool = null;
let lastSig = '';
let saveQueue = Promise.resolve();
let saveTimer = null;

function snapshot() {
  const people = new Map();
  for (const g of ghosts.values()) {
    people.set(g.id, { id: g.id, key: g.key, nick: g.nick, color: g.color, lastRoomId: g.lastRoomId, lastChannelId: g.lastChannelId });
  }
  // 지금 접속 중이거나 막 끊긴 사람도 '돌아오면 원래 방으로 복귀할 사람'으로 저장
  for (const u of users.values()) {
    people.set(u.id, { id: u.id, key: u.key, nick: u.nick, color: u.color, lastRoomId: u.roomId, lastChannelId: u.channelId });
  }
  return {
    v: 1,
    rooms: [...rooms.values()].map((r) => ({
      id: r.id,
      name: r.name,
      ownerId: r.ownerId,
      passHash: r.passHash,
      createdAt: r.createdAt,
      emptySince: r.emptySince,
      known: [...r.known],
      channels: [...r.channels.values()].map((c) => ({ id: c.id, name: c.name, createdBy: c.createdBy, members: c.members, isDefault: c.isDefault })),
    })),
    people: [...people.values()],
  };
}

function restore(data) {
  if (!data || data.v !== 1) return;
  const now = Date.now();
  for (const r of data.rooms || []) {
    const channels = new Map();
    for (const c of r.channels || []) {
      channels.set(c.id, { id: c.id, name: c.name, createdBy: c.createdBy, members: c.members ?? null, isDefault: !!c.isDefault, talker: null, talkStarted: 0 });
    }
    if (!channels.size) continue;
    rooms.set(r.id, {
      id: r.id,
      name: r.name,
      ownerId: r.ownerId,
      passHash: r.passHash ?? null,
      channels,
      known: new Map(r.known || []),
      emptySince: r.emptySince ?? now, // 재시작 직후엔 아무도 없으니 빈 방 시계를 시작
      createdAt: r.createdAt || now,
    });
  }
  for (const p of data.people || []) {
    ghosts.set(p.id, { id: p.id, key: p.key, nick: p.nick, color: p.color, lastRoomId: p.lastRoomId, lastChannelId: p.lastChannelId, at: now });
    keys.set(p.key, p.id);
  }
}

function saveState(force = false) {
  saveQueue = saveQueue.then(async () => {
    if (!pool) return;
    try {
      const json = JSON.stringify(snapshot());
      if (!force && json === lastSig) return;
      await pool.query(
        'INSERT INTO walkie_state (id, data, updated_at) VALUES (1, $1::jsonb, now()) ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = now()',
        [json]
      );
      lastSig = json;
    } catch (e) {
      console.error('저장 실패(서버는 계속 동작):', e.message);
    }
  });
  return saveQueue;
}

async function initStorage() {
  if (!process.env.DATABASE_URL) {
    console.log('DATABASE_URL 없음 → 메모리 방식으로 동작(서버가 재시작되면 방이 사라짐)');
    return;
  }
  try {
    const { Pool } = require('pg');
    pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 2, idleTimeoutMillis: 30_000, connectionTimeoutMillis: 15_000 });
    // Neon은 한가하면 잠들면서 연결을 끊음 → 에러 이벤트를 받아 주지 않으면 서버가 죽음
    pool.on('error', (e) => console.error('DB 연결 오류(자동 재연결):', e.message));
    await pool.query('CREATE TABLE IF NOT EXISTS walkie_state (id INT PRIMARY KEY, data JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())');
    const { rows } = await pool.query('SELECT data FROM walkie_state WHERE id = 1');
    if (rows[0]) restore(rows[0].data);
    lastSig = JSON.stringify(snapshot());
    console.log(`DB 연결됨: 방 ${rooms.size}개, 자리 ${ghosts.size}명 복원`);
    saveTimer = setInterval(() => saveState(), 5_000);
  } catch (e) {
    console.error('DB 연결 실패 → 메모리 방식으로 동작:', e.message);
    try { await pool?.end(); } catch {}
    pool = null;
  }
}

async function shutdown() {
  clearInterval(saveTimer);
  // 서버가 꺼지기 직전에 마지막으로 한 번 저장(최대 4초 대기)
  await Promise.race([saveState(true), new Promise((r) => setTimeout(r, 4_000))]);
  process.exit(0);
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

initStorage().finally(() => {
  server.listen(PORT, () => {
    console.log(`무전기 서버 실행 중: http://localhost:${PORT}`);
  });
});
