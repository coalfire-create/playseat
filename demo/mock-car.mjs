#!/usr/bin/env node
/**
 * MockCarClient — 차량 앱 흉내 내기
 *
 * 동승자 웹앱을 차량 앱 없이 개발·테스트하기 위한 목(mock)입니다.
 * contract/protocol.md v0.1 의 차량 측 역할을 그대로 수행합니다.
 *   - state.sync 를 단일 진실 공급원으로 내려보냅니다
 *   - seat.claim / queue.add / vote.skip / turn.pass 를 검증하고 거절합니다
 *
 * 의존성 없음 (Node 22+ 내장 WebSocket 사용).
 *
 *   node demo/mock-car.mjs
 *   node demo/mock-car.mjs --session a3f9 --host localhost:8080
 *
 * 실행 중 키 입력:
 *   d  주행/정차 전환      1~5  좌석 착석 토글      n  다음 곡      q  종료
 */

const args = process.argv.slice(2);
const argOf = (k, d) => {
  const i = args.indexOf(`--${k}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : d;
};
const SESSION = argOf("session", "a3f9");
const HOST = argOf("host", "localhost:8080");
const URL = `ws://${HOST}/session/${SESSION}?role=car`;

const SEAT_ORDER = ["R1R", "R2L", "R2C", "R2R", "R1L"]; // 운전석(R1L)이 마지막 차례
const PROFILE = { R1L: "DRIVER", R1R: "ADULT", R2L: "ADULT", R2C: "CHILD", R2R: "CHILD" };

let itemSeq = 1;
const state = {
  sessionId: SESSION,
  driveState: "PARKED",
  seats: ["R1L", "R1R", "R2L", "R2C", "R2R"].map((seatId) => ({
    seatId,
    occupied: seatId === "R1L" || seatId === "R1R" || seatId === "R2L",
    deviceId: null,
    nickname: null,
    profile: PROFILE[seatId],
    connected: false,
  })),
  turnSeatId: null,
  turnDeadlineMs: 30000,
  nowPlaying: null,
  queue: [],
  etaSeconds: 9600,
  queueTargetMs: 9600 * 1000,
  skipVotes: [],
  skipThreshold: 2,
};

const seat = (id) => state.seats.find((s) => s.seatId === id);
const occupied = () => SEAT_ORDER.filter((id) => seat(id)?.occupied);

function advanceTurn() {
  const list = occupied();
  if (list.length === 0) {
    state.turnSeatId = null;
    return;
  }
  const at = list.indexOf(state.turnSeatId);
  state.turnSeatId = list[(at + 1) % list.length];
}

function recomputeThreshold() {
  state.skipThreshold = Math.max(2, Math.ceil(occupied().length / 2));
}

let ws;
const send = (obj) => ws?.readyState === 1 && ws.send(JSON.stringify({ ts: Date.now(), ...obj }));
const sync = () => send({ type: "state.sync", ...state });
const reject = (requestType, reason, seatId) =>
  send({ type: "reject", requestType, reason, seatId });

function playNext() {
  const next = state.queue.shift() || null;
  state.nowPlaying = next ? { ...next, positionMs: 0 } : null;
  state.skipVotes = [];
  if (next) console.log(`  ▶ 재생: ${next.title} — ${next.artist} (${next.requestedBySeat})`);
}

function handle(msg) {
  const { type } = msg;

  if (type === "seat.claim") {
    const s = seat(msg.seatId);
    if (!s) return reject(type, "SEAT_TAKEN", msg.seatId);
    if (!s.occupied) return reject(type, "SEAT_TAKEN", msg.seatId);
    if (s.deviceId && s.deviceId !== msg.deviceId) return reject(type, "SEAT_TAKEN", msg.seatId);
    s.deviceId = msg.deviceId;
    s.nickname = msg.nickname || null;
    s.connected = true;
    console.log(`  좌석 참여: ${msg.seatId} ← ${msg.nickname || msg.deviceId}`);
    if (!state.turnSeatId) advanceTurn();
    return sync();
  }

  if (type === "queue.add") {
    if (state.turnSeatId !== msg.seatId) return reject(type, "NOT_YOUR_TURN", msg.seatId);
    const s = seat(msg.seatId);
    if (s?.profile === "CHILD" && (msg.durationMs || 0) > 400000)
      return reject(type, "PROFILE_BLOCKED", msg.seatId);
    const dup = state.queue.filter((q) => q.requestedBySeat === msg.seatId).length;
    if (dup >= 2) return reject(type, "PROFILE_BLOCKED", msg.seatId);
    state.queue.push({
      itemId: `q_${itemSeq++}`,
      title: msg.title,
      artist: msg.artist,
      durationMs: msg.durationMs || 210000,
      requestedBySeat: msg.seatId,
    });
    console.log(`  큐 추가: ${msg.title} — ${msg.artist} (${msg.seatId})`);
    if (!state.nowPlaying) playNext();
    advanceTurn();
    return sync();
  }

  if (type === "queue.remove") {
    const i = state.queue.findIndex((q) => q.itemId === msg.itemId);
    if (i < 0) return;
    if (state.queue[i].requestedBySeat !== msg.seatId)
      return reject(type, "PROFILE_BLOCKED", msg.seatId);
    state.queue.splice(i, 1);
    return sync();
  }

  if (type === "vote.skip") {
    if (state.driveState === "DRIVING" && seat(msg.seatId)?.profile === "DRIVER")
      return reject(type, "DRIVING_RESTRICTED", msg.seatId);
    if (state.skipVotes.includes(msg.seatId)) return reject(type, "DUPLICATE_VOTE", msg.seatId);
    state.skipVotes.push(msg.seatId);
    console.log(`  스킵 투표: ${state.skipVotes.length}/${state.skipThreshold}`);
    if (state.skipVotes.length >= state.skipThreshold) playNext();
    return sync();
  }

  if (type === "reaction.like") {
    console.log(`  ♥ ${msg.seatId}`);
    return;
  }

  if (type === "turn.pass") {
    if (state.turnSeatId !== msg.seatId) return reject(type, "NOT_YOUR_TURN", msg.seatId);
    advanceTurn();
    return sync();
  }
}

function connect() {
  console.log(`접속: ${URL}`);
  ws = new WebSocket(URL);

  ws.onopen = () => {
    console.log("차량(목) 연결됨. 키: d=주행전환 1~5=좌석토글 n=다음곡 q=종료");
    recomputeThreshold();
    if (!state.turnSeatId) advanceTurn();
    sync();
    if (state.turnSeatId) send({ type: "turn.notify", seatId: state.turnSeatId, deadlineMs: 30000 });
  };

  ws.onmessage = (e) => {
    let msg;
    try { msg = JSON.parse(e.data); } catch { return; }
    handle(msg);
  };

  ws.onclose = () => {
    console.log("연결 끊김. 3초 후 재접속합니다.");
    setTimeout(connect, 3000);
  };

  ws.onerror = () => {};
}

// 재생 위치 진행 + 주기적 상태 송신
setInterval(() => {
  if (state.nowPlaying) {
    state.nowPlaying.positionMs += 1000;
    if (state.nowPlaying.positionMs >= state.nowPlaying.durationMs) playNext();
  }
  if (state.etaSeconds > 0) state.etaSeconds -= 1;
  sync();
}, 1000);

// 키 입력으로 차량 신호 주입 (Virtual Console 대역)
if (process.stdin.isTTY) {
  process.stdin.setRawMode(true);
  process.stdin.resume();
  process.stdin.on("data", (buf) => {
    const k = buf.toString();
    if (k === "q" || k === "\u0003") process.exit(0);
    if (k === "d") {
      state.driveState = state.driveState === "PARKED" ? "DRIVING" : "PARKED";
      console.log(`  주행 상태: ${state.driveState}`);
      sync();
    }
    const idx = "12345".indexOf(k);
    if (idx >= 0) {
      const s = state.seats[idx];
      s.occupied = !s.occupied;
      if (!s.occupied) {
        s.deviceId = null; s.nickname = null; s.connected = false;
        state.queue = state.queue.filter((q) => q.requestedBySeat !== s.seatId);
        state.skipVotes = state.skipVotes.filter((v) => v !== s.seatId);
        if (state.turnSeatId === s.seatId) advanceTurn();
      }
      recomputeThreshold();
      console.log(`  ${s.seatId} 착석=${s.occupied}`);
      sync();
    }
    if (k === "n") { playNext(); sync(); }
  });
}

connect();
