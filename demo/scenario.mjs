#!/usr/bin/env node
/**
 * 시연 시나리오 자동 실행.
 *
 * 접수양식 '시나리오 ① 가족 주말 나들이'를 에뮬레이터에서 그대로 재생합니다.
 * 차량 신호는 Pleos Virtual Console 로, 동승자는 폰 흉내 클라이언트로 넣습니다.
 * 녹화하는 사람은 화면만 잡으면 됩니다 — 손으로 눌러야 하는 것이 없습니다.
 *
 * 장면 순서에 이유가 있습니다
 *   타자마자는 큐가 비어 있습니다. 바로 거기서 '아무도 안 넣으면 차가 권한다' 가
 *   자연스럽게 일어납니다. 사람이 고르는 장면을 먼저 넣으면 그 뒤로는 큐가 비지
 *   않아 추천 장면을 억지로 만들어야 합니다.
 *
 * 준비
 *   1) 에뮬레이터 실행 + 차량 앱 설치
 *   2) adb forward tcp:8088 tcp:8088
 *   3) cd hub-server && ./gradlew run
 *   4) (선택) 브라우저로 http://localhost:8080 을 열어 두면 '2열 좌' 자리에
 *      사람이 쓰는 폰 화면이 그대로 들어갑니다. 좌석은 스크립트가 미리 잡아 둡니다.
 *
 * 실행
 *   node demo/scenario.mjs                 # 전체 (약 3분)
 *   node demo/scenario.mjs --fast          # 대기 시간 단축 (리허설용)
 *   node demo/scenario.mjs --step          # 장면마다 Enter (라이브 시연·PT)
 *   node demo/scenario.mjs --reset         # 신호만 초기화하고 종료
 *   node demo/scenario.mjs --from 5        # 5번 장면부터
 *   node demo/scenario.mjs --log cue.json  # 장면 시각을 기록 (영상 편집용)
 *   node demo/scenario.mjs --no-restart    # 앱을 다시 시작하지 않음
 */

import { Console, sleep } from "./console.mjs";
import { Phone, CAST } from "./phones.mjs";
import { createInterface } from "node:readline/promises";
import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";

const args = process.argv.slice(2);
const has = (f) => args.includes(`--${f}`);
const argOf = (f, d) => { const i = args.indexOf(`--${f}`); return i >= 0 ? args[i + 1] : d; };

const FAST = has("fast");
const STEP = has("step");
const FROM = Number(argOf("from", 1));
const LOG = argOf("log", null);
const scale = FAST ? 0.35 : 1;

const rl = STEP ? createInterface({ input: process.stdin, output: process.stdout }) : null;
const wait = async (sec) => { if (!STEP) await sleep(sec * 1000 * scale); };
/** 차가 정한 시간(추천 카운트다운 등)은 줄이면 안 됩니다 — 줄이면 장면이 안 나옵니다. */
const waitReal = async (sec) => { if (!STEP) await sleep(sec * 1000); };

/* ---------- 장면 기록 ----------
   영상 편집은 '몇 초에 무슨 일이 있었나' 를 알아야 합니다. 눈으로 다시 보면서
   찾으면 다시 녹화할 때마다 처음부터 해야 하므로, 시각을 같이 남깁니다. */
const t0 = Date.now();
const cues = [];
function mark(kind, text) {
  const t = (Date.now() - t0) / 1000;
  cues.push({ t: Math.round(t * 100) / 100, kind, text });
  if (LOG) writeFileSync(LOG, JSON.stringify({ startedAt: t0, cues }, null, 1));
}

let n = 0;
async function scene(title, caption, body) {
  n += 1;
  if (n < FROM) return;
  console.log(`\n── ${n}. ${title}`);
  if (STEP) await rl.question("   (Enter)");
  mark("scene", caption ?? title);
  await body();
}

const adb = (...a) => { try { execFileSync("adb", a, { stdio: "ignore" }); } catch {} };

if (!has("no-restart") && !has("reset")) {
  // 세션 상태(좌석·큐·추천 스누즈)는 차량 앱이 들고 있습니다. 지난 실행이 남아 있으면
  // 같은 장면이 안 나옵니다.
  adb("shell", "am", "force-stop", "--user", "10", "dev.semicolon.playseat.car");
  adb("shell", "svc", "power", "stayon", "true");
  adb("shell", "am", "start", "--user", "10",
      "-n", "dev.semicolon.playseat.car/.MainActivity");
  await sleep(6000);
}

const car = await new Console().open();

/** 신호를 전부 초기 상태로. 시연은 늘 같은 자리에서 시작해야 합니다. */
async function reset() {
  await car.gear("Park");
  await car.wiper("Off");
  await car.headlamp("Off");
  await car.steering(0);
  for (let i = 0; i < 5; i++) {
    await car.charging(false);
    await sleep(900);
    if ((await car.state()).ev_battery?.charging_activated === false) break;
  }
  await car.seats({ R1L: false, R1R: false, R2L: false, R2R: false });
}

if (has("reset")) {
  await reset();
  console.log("초기화했습니다.");
  car.close();
  process.exit(0);
}

console.log("플레이시트 시연 — 가족 주말 나들이");
console.log(FAST ? "(빠른 모드)" : STEP ? "(장면마다 Enter)" : "(약 3분)");
await reset();
await sleep(2000);
mark("start", "녹화 시작점");

const phones = [];

await scene("탑승 — 좌석이 곧 입장권", "좌석이 곧 입장권", async () => {
  // 한 사람씩 탑니다. 한꺼번에 넣으면 '감지되는 과정' 이 안 보입니다.
  for (const [seat, who] of [["R1L", "운전석"], ["R1R", "조수석"], ["R2L", "2열 좌"], ["R2R", "2열 우"]]) {
    await car.seats({ [seat]: true });
    console.log(`   ${who} 착석`);
    await wait(1.6);
  }
  console.log("   → 좌석도가 채워지고 선곡 순서 번호가 붙습니다");
  await wait(2);
});

await scene("폰 연결 — 최초 1회 QR, 다음부터 자동", "최초 1회 QR, 다음부터 자동 연결", async () => {
  for (const p of CAST) {
    phones.push(await new Phone(p).connect());
    console.log(`   ${p.nickname} (${p.seatId}) 연결`);
    await wait(1.2);
  }
  // 2열 좌는 브라우저가 쓸 자리입니다. 좌석만 잡아 두고 바로 끊습니다 —
  // 같은 deviceId 로 둘이 붙으면 허브가 하나를 끊습니다.
  const seatBot = await new Phone({
    deviceId: "demo-r2l", seatId: "R2L", nickname: "지호",
  }).connect();
  await sleep(1500);
  seatBot.close();
  console.log("   → 운전석은 폰을 붙이지 않습니다. 음성으로만 참여합니다");
  await wait(2);
});

await scene("아무도 안 넣을 때 — 차가 먼저 권한다", "아무도 안 넣으면 차가 먼저 권한다", async () => {
  console.log("   아직 아무도 곡을 넣지 않았습니다");
  for (let i = 0; i < 40; i++) {
    if ((phones[0].state?.suggestions ?? []).length) break;
    await sleep(1000);
  }
  mark("beat", "추천이 떴습니다 — 20초 카운트다운");
  console.log("   → 추천 5곡 + 20초 카운트다운. 폰에서 '괜찮아요' 를 누르면 담지 않습니다");
  for (let i = 0; i < 30; i++) {
    if (phones[0].state?.nowPlaying) break;
    await sleep(1000);
  }
  mark("beat", "아무도 안 눌러서 그대로 담겼습니다");
  console.log(`   → 담긴 곡: ${phones[0].state?.nowPlaying?.title ?? "(없음)"}`);
  await wait(3);
});

await scene("듣고 싶은 걸 그냥 넣는다", "차례를 기다리지 않습니다", async () => {
  // 차례가 없으므로 누가 먼저 넣든 상관없습니다. 듣고 싶은 곡이 생긴 순간이
  // 넣는 순간이고, 그게 플레이리스트입니다.
  for (const [who, q] of [["엄마", "아이유"], ["서아", "아기상어"]]) {
    const p = phones.find((x) => x.nickname === who);
    const t = await p.add(q);
    mark("beat", `${who} → ${t?.title ?? q}`);
    console.log(`   ${who} → ${t ? `${t.title} — ${t.artist}` : q}`);
    await wait(3);
  }
});

await scene("한 사람이 연달아 넣어도 됩니다", "한 사람이 연달아 넣어도 막지 않습니다", async () => {
  // 독점을 규칙으로 막지 않습니다. 같이 탄 사람들은 서로 보고 있고,
  // 그래도 불만이면 스킵 투표가 있습니다.
  const p = phones.find((x) => x.nickname === "서아");
  for (const q of ["뽀로로", "핑크퐁"]) {
    const t = await p.add(q);
    mark("beat", `서아 → ${t?.title ?? q}`);
    console.log(`   서아 → ${t ? `${t.title} — ${t.artist}` : q}`);
    await wait(2.5);
  }
});

await scene("주행 시작 — 조작 권한이 폰으로 이사", "주행하면 조작 권한이 동승자 폰으로", async () => {
  await car.gear("Drive");
  console.log("   기어 D");
  console.log("   → 운전자 화면이 '지금 곡 + 버튼 2개' 로 축약됩니다");
  await wait(8);
});

await scene("비가 옵니다 — 와이퍼가 분위기를 바꿉니다", "날씨 API가 아니라 와이퍼로 읽는다", async () => {
  await car.wiper("Low");
  console.log("   와이퍼 Low → 분위기 '비 오는 날'");
  console.log("   → 차량 화면·폰 배경·실내 앰비언트 조명이 같은 색으로 바뀝니다");
  await wait(9);
});

await scene("터널 — 전조등이 켜지면 밤처럼", "전조등이 켜지면 밤처럼", async () => {
  await car.wiper("Off");
  await car.headlamp("LowBeam");
  console.log("   → 분위기 '밤 드라이브' · 근거는 '전조등이 켜져 있어요'");
  await wait(8);
});

await scene("굽잇길 — 조향 변동으로 지형을 읽습니다", "지도 API 없이 조향 변동으로 지형을", async () => {
  await car.headlamp("Off");
  // 한 번 크게 꺾는 것과 계속 흔들리는 것은 다릅니다. 12초 창의 변동폭을 봅니다.
  for (let i = 0; i < 9; i++) {
    await car.steering(i % 2 ? -75 : 75);
    await sleep(800);
  }
  console.log("   → 분위기 '굽잇길'");
  await wait(4);
});

await scene("도착 — 기어 P, 운전자 화면이 다시 열립니다", "정차하면 운전자 화면이 다시 열린다", async () => {
  await car.steering(0);
  await car.gear("Park");
  console.log("   → 전체 큐·검색·좌석 현황이 돌아옵니다");
  await wait(7);
});

await scene("충전 라운지 — 정차해 있고 시간이 있다", "충전 중에는 라운지 모드", async () => {
  await car.charging(true);
  console.log("   충전 시작 → 라운지 모드");
  await wait(4);
  for (const p of phones) { await p.add(p.nickname === "엄마" ? "폴킴" : "핑크퐁"); await sleep(900); }
  console.log("   → 충전 중임이 화면에 표시되고, 분위기도 라운지로 바뀝니다");
  await wait(6);
});

await scene("하차 — 좌석 회수와 드라이브 앨범", "도착하면 드라이브 앨범이 남는다", async () => {
  await car.charging(false);
  phones.forEach((p) => p.close());
  await car.seats({ R1L: false, R1R: false, R2L: false, R2R: false });
  console.log("   → 좌석 슬롯 회수, 드라이브 앨범 전송");
  await wait(6);
});

mark("end", "녹화 끝점");
console.log("\n끝났습니다.");
if (LOG) console.log(`장면 기록: ${LOG}`);
car.close();
rl?.close();
process.exit(0);
