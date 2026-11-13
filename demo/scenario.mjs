#!/usr/bin/env node
/**
 * 시연 시나리오 자동 실행.
 *
 * 접수양식 '시나리오 ① 가족 주말 나들이'를 에뮬레이터에서 그대로 재생합니다.
 * 차량 신호는 Pleos Virtual Console 로, 동승자는 폰 흉내 클라이언트로 넣습니다.
 * 녹화하는 사람은 화면만 잡으면 됩니다 — 손으로 눌러야 하는 것이 없습니다.
 *
 * 준비
 *   1) 에뮬레이터 실행 + 차량 앱 설치·실행
 *   2) adb forward tcp:8088 tcp:8088
 *   3) cd hub-server && ./gradlew run
 *   4) (선택) 폰 브라우저나 데스크톱 브라우저로 http://localhost:8080 을 열고
 *      '2열 좌'를 직접 잡으면, 사람이 쓰는 화면이 영상에 같이 들어갑니다.
 *
 * 실행
 *   node demo/scenario.mjs            # 전체 (약 4분)
 *   node demo/scenario.mjs --fast     # 대기 시간 단축 (리허설용)
 *   node demo/scenario.mjs --step     # 장면마다 Enter 를 눌러 진행 (라이브 시연용)
 *   node demo/scenario.mjs --reset    # 신호만 초기화하고 종료
 *   node demo/scenario.mjs --from 5   # 5번 장면부터
 */

import { Console, sleep } from "./console.mjs";
import { Phone, CAST } from "./phones.mjs";
import { createInterface } from "node:readline/promises";

const args = process.argv.slice(2);
const has = (f) => args.includes(`--${f}`);
const numOf = (f, d) => { const i = args.indexOf(`--${f}`); return i >= 0 ? Number(args[i + 1]) : d; };

const FAST = has("fast");
const STEP = has("step");
const FROM = numOf("from", 1);
const scale = FAST ? 0.35 : 1;

const rl = STEP ? createInterface({ input: process.stdin, output: process.stdout }) : null;
const wait = async (sec) => { if (!STEP) await sleep(sec * 1000 * scale); };
/** 차가 정한 시간(추천 카운트다운 등)은 줄이면 안 됩니다 — 줄이면 장면이 안 나옵니다. */
const waitReal = async (sec) => { if (!STEP) await sleep(sec * 1000); };

let n = 0;
async function scene(title, body) {
  n += 1;
  if (n < FROM) return;
  console.log(`\n── ${n}. ${title}`);
  if (STEP) await rl.question("   (Enter)");
  await body();
}

const car = await new Console().open();

/** 신호를 전부 초기 상태로. 시연은 늘 같은 자리에서 시작해야 합니다. */
async function reset() {
  await car.gear("Park");
  await car.wiper("Off");
  await car.headlamp("Off");
  await car.steering(0);
  await car.charging(false);
  await car.seats({ R1L: false, R1R: false, R2L: false, R2R: false });
}

if (has("reset")) {
  await reset();
  console.log("초기화했습니다.");
  car.close();
  process.exit(0);
}

console.log("플레이시트 시연 — 가족 주말 나들이");
console.log(FAST ? "(빠른 모드)" : STEP ? "(장면마다 Enter)" : "(약 4분)");
await reset();
await sleep(1500);

const phones = [];

await scene("탑승 — 좌석이 곧 입장권", async () => {
  // 도어를 열고 한 사람씩 탑니다. 한꺼번에 넣으면 '감지되는 과정'이 안 보입니다.
  for (const [seat, who] of [["R1L", "운전석"], ["R1R", "조수석"], ["R2L", "2열 좌"], ["R2R", "2열 우"]]) {
    await car.seats({ [seat]: true });
    console.log(`   ${who} 착석`);
    await wait(1.5);
  }
  console.log("   → 차량 화면의 좌석도가 채워지고 선곡 순서 번호가 붙습니다");
});

await scene("폰 연결 — 최초 1회 QR, 다음부터 자동", async () => {
  for (const p of CAST) {
    phones.push(await new Phone(p).connect());
    console.log(`   ${p.nickname} (${p.seatId}) 연결`);
    await wait(1.2);
  }
  console.log("   → 운전석은 폰을 붙이지 않습니다. 음성으로만 참여합니다");
  console.log("   → '2열 좌'는 비워 뒀습니다. 브라우저로 직접 잡으면 실제 폰 화면이 됩니다");
});

await scene("좌석 턴 — 차례대로 한 곡씩", async () => {
  const picks = [["엄마", "아이유"], ["서아", "아기상어"]];
  for (const [who, q] of picks) {
    const p = phones.find((x) => x.nickname === who);
    // 자기 차례가 아니면 차량이 거절합니다. 차례가 올 때까지 기다립니다.
    for (let i = 0; i < 40; i++) {
      if (p.state?.turnSeatId === p.seatId) break;
      await sleep(1000);
    }
    const t = await p.add(q);
    console.log(`   ${who} → ${t ? `${t.title} — ${t.artist}` : q}`);
    await wait(2);
  }
});

await scene("주행 시작 — 조작 권한이 폰으로 이사", async () => {
  await car.gear("Drive");
  console.log("   기어 D");
  console.log("   → 운전자 화면이 '지금 곡 + 버튼 2개'로 축약됩니다");
  console.log("   → 동승자 폰에 '선곡 조작은 이 폰에 있습니다' 가 뜹니다");
  await wait(6);
});

await scene("비가 옵니다 — 와이퍼가 분위기를 바꿉니다", async () => {
  await car.wiper("Low");
  console.log("   와이퍼 Low → 분위기 '비 오는 날'");
  console.log("   → 화면 배경·폰 배경·실내 앰비언트 조명이 같은 색으로 바뀝니다");
  await wait(8);
});

await scene("굽잇길 — 조향 변동으로 지형을 읽습니다", async () => {
  await car.wiper("Off");
  // 한 번 크게 꺾는 것과 계속 흔들리는 것은 다릅니다. 12초 창의 변동폭을 봅니다.
  for (let i = 0; i < 8; i++) {
    await car.steering(i % 2 ? -70 : 70);
    await sleep(900);
  }
  console.log("   → 분위기 '굽잇길'. 지도 API 없이 조향만으로 판정합니다");
  await wait(4);
});

await scene("터널 — 전조등이 켜지면 밤처럼", async () => {
  await car.steering(0);
  await car.headlamp("LowBeam");
  console.log("   → 분위기 '밤 드라이브' · 근거는 '전조등이 켜져 있어요'");
  await wait(7);
});

await scene("아무도 안 넣을 때 — 차가 먼저 권합니다", async () => {
  // 큐를 비웁니다. 과반 스킵으로 지금 곡까지 내보냅니다.
  for (let i = 0; i < 10; i++) {
    const s = phones[0].state;
    if (!s?.nowPlaying && !(s?.queue ?? []).length) break;
    phones.forEach((p) => p.skip());
    await sleep(1200);
  }
  console.log("   큐가 비었습니다. 12초 뒤 추천이 뜨고 20초 뒤 자동으로 담깁니다");
  console.log("   → 폰에서 '괜찮아요' 를 누르면 담지 않습니다 (차례와 무관)");
  await waitReal(26);
  const s = phones[0].state;
  console.log(`   → 담긴 곡: ${s?.nowPlaying?.title ?? "(없음)"}`);
});

await scene("도착 — 기어 P, 운전자 화면이 다시 열립니다", async () => {
  await car.headlamp("Off");
  await car.gear("Park");
  console.log("   → 전체 큐·검색·좌석 현황이 운전자 화면에 돌아옵니다");
  await wait(6);
});

await scene("충전 라운지 — 차례 없이 모두 추가", async () => {
  await car.charging(true);
  console.log("   충전 시작 → 라운지 모드");
  await wait(3);
  for (const p of phones) { await p.add(p.nickname === "엄마" ? "폴킴" : "핑크퐁"); await sleep(800); }
  console.log("   → 차례를 기다리지 않고 두 좌석이 동시에 넣습니다");
  await wait(5);
});

await scene("정리", async () => {
  await car.charging(false);
  phones.forEach((p) => p.close());
  await car.seats({ R1L: false, R1R: false, R2L: false, R2R: false });
  console.log("   하차 → 좌석 슬롯 회수, 드라이브 앨범 전송");
  await wait(4);
  await reset();
});

console.log("\n끝났습니다.");
car.close();
rl?.close();
process.exit(0);
