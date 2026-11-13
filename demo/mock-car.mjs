#!/usr/bin/env node
/**
 * MockCarClient — 차량 앱 흉내 내기
 *
 * 동승자 웹앱을 차량 앱 없이 개발·테스트하기 위한 목(mock)입니다.
 * contract/protocol.md v0.1 의 차량 측 역할을 그대로 수행합니다.
 *   - state.sync 를 단일 진실 공급원으로 내려보냅니다
 *   - seat.claim / queue.add / vote.skip 을 검증하고 거절합니다
 *
 * 의존성 없음 (Node 22+ 내장 WebSocket 사용).
 *
 *   node demo/mock-car.mjs
 *   node demo/mock-car.mjs --session a3f9 --host localhost:8080
 *   node demo/mock-car.mjs --charging          # 충전 라운지 모드로 시작
 *   node demo/mock-car.mjs --driving --eta 240 # 주행 + 랜딩 구간으로 시작
 *
 * 실행 중 키 입력:
 *   d  주행/정차 전환   c  충전 토글(정차 시 라운지 모드)   e  ETA 4분(랜딩 구간)
 *   1~5  좌석 착석 토글   n  다음 곡   q  종료
 */

const args = process.argv.slice(2);
const argOf = (k, d) => {
  const i = args.indexOf(`--${k}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : d;
};
const SESSION = argOf("session", "a3f9");
const START_ETA = Number(argOf("eta", "9600"));
const START_DRIVING = args.includes("--driving");
const HOST = argOf("host", "localhost:8080");
const URL = `ws://${HOST}/session/${SESSION}?role=car`;

const SEAT_IDS = ["R1L", "R1R", "R2L", "R2C", "R2R"];
const PROFILE = { R1L: "DRIVER", R1R: "ADULT", R2L: "ADULT", R2C: "CHILD", R2R: "CHILD" };

let itemSeq = 1;
const state = {
  sessionId: SESSION,
  driveState: START_DRIVING ? "DRIVING" : "PARKED",
  seats: ["R1L", "R1R", "R2L", "R2C", "R2R"].map((seatId) => ({
    seatId,
    occupied: seatId === "R1L" || seatId === "R1R" || seatId === "R2L",
    deviceId: null,
    nickname: null,
    profile: PROFILE[seatId],
    connected: false,
  })),
  nowPlaying: null,
  queue: [],
  etaSeconds: START_ETA,
  queueTargetMs: START_ETA * 1000,
  skipVotes: [],
  skipThreshold: 2,
  mode: "NORMAL",
  landing: false,
  queueTotalMs: 0,
};

let charging = args.includes("--charging");
let tripStartedAt = null;
let tripTracks = [];

const seat = (id) => state.seats.find((s) => s.seatId === id);
const occupied = () => SEAT_IDS.filter((id) => seat(id)?.occupied);

const LANDING_SECONDS = 300;

/** 차량이 내려보내기 전에 파생 필드를 맞춥니다. */
function derive() {
  const driving = state.driveState === "DRIVING";
  state.queueTotalMs = state.queue.reduce((a, q) => a + q.durationMs, 0);
  state.mode = charging && !driving ? "CHARGING_LOUNGE" : "NORMAL";
  state.landing = driving && state.etaSeconds != null && state.etaSeconds <= LANDING_SECONDS;
}

function emitAlbum() {
  if (!tripStartedAt || tripTracks.length === 0) {
    tripStartedAt = null; tripTracks = [];
    return;
  }
  const endedAt = Date.now();
  send({
    type: "drive.album",
    startedAt: tripStartedAt,
    endedAt,
    durationMs: endedAt - tripStartedAt,
    tracks: tripTracks,
    seats: state.seats.filter((s) => s.nickname).map((s) => ({ seatId: s.seatId, nickname: s.nickname })),
  });
  console.log(`  드라이브 앨범 전송: ${tripTracks.length}곡`);
  tripStartedAt = null;
  tripTracks = [];
}

function setDriveState(next) {
  const was = state.driveState;
  state.driveState = next;
  if (was !== "DRIVING" && next === "DRIVING") {
    tripStartedAt = Date.now();
    tripTracks = [];
  } else if (was === "DRIVING" && next !== "DRIVING") {
    emitAlbum();
  }
}

function recomputeThreshold() {
  state.skipThreshold = Math.max(2, Math.ceil(occupied().length / 2));
}

let ws;
const send = (obj) => ws?.readyState === 1 && ws.send(JSON.stringify({ ts: Date.now(), ...obj }));
const sync = () => { derive(); send({ type: "state.sync", ...state }); };
const reject = (requestType, reason, seatId) =>
  send({ type: "reject", requestType, reason, seatId });

function playNext() {
  if (state.nowPlaying) {
    if (!tripStartedAt) tripStartedAt = Date.now();
    tripTracks.push({
      title: state.nowPlaying.title,
      artist: state.nowPlaying.artist,
      requestedBySeat: state.nowPlaying.requestedBySeat,
    });
  }
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
    return sync();
  }

  if (type === "queue.add") {
    // 앉아 있으면 누구나, 언제든, 몇 곡이든. 차례도 좌석별 상한도 없습니다.
    const s = seat(msg.seatId);
    if (!s?.occupied) return reject(type, "SEAT_TAKEN", msg.seatId);
    if (s.profile === "CHILD" && (msg.durationMs || 0) > 400000)
      return reject(type, "PROFILE_BLOCKED", msg.seatId);
    state.queue.push({
      itemId: `q_${itemSeq++}`,
      title: msg.title,
      artist: msg.artist,
      durationMs: msg.durationMs || 210000,
      requestedBySeat: msg.seatId,
    });
    console.log(`  큐 추가: ${msg.title} — ${msg.artist} (${msg.seatId})`);
    if (!state.nowPlaying) playNext();
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

}

function connect() {
  console.log(`접속: ${URL}`);
  ws = new WebSocket(URL);

  ws.onopen = () => {
    console.log("차량(목) 연결됨. 키: d=주행전환 c=충전 e=ETA4분 1~5=좌석 n=다음곡 q=종료");
    recomputeThreshold();
    sync();
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
  if (state.driveState === "DRIVING" && state.etaSeconds > 0) state.etaSeconds -= 1;
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
      setDriveState(state.driveState === "PARKED" ? "DRIVING" : "PARKED");
      console.log(`  주행 상태: ${state.driveState}`);
      sync();
    }
    if (k === "c") {
      charging = !charging;
      console.log(`  충전: ${charging}${charging && state.driveState !== "DRIVING" ? " → 라운지 모드" : ""}`);
      sync();
    }
    if (k === "e") {
      state.etaSeconds = 240;
      console.log("  ETA 4분 — 랜딩 구간 진입");
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
      }
      recomputeThreshold();
      console.log(`  ${s.seatId} 착석=${s.occupied}`);
      sync();
    }
    if (k === "n") { playNext(); sync(); }
  });
}

connect();
