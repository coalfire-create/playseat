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


/* ---------- 영상 속 폰 ----------
   record.mjs 가 띄운 헤드리스 Chrome(포트 9223)의 폰 화면을 CDP 로 몰아
   사람이 하듯 글자를 치고, 검색하고, '추가' 와 '건너뛰기' 를 누릅니다.
   흉내 클라이언트(phones.mjs)만 쓰면 폰 화면은 늘 구경만 하는 폰이 됩니다 —
   '각자 폰으로 넣는다' 가 이 앱의 명제인데 그 장면이 없으면 안 됩니다. */
class BrowserPhone {
  #ws; #id = 0; #w = new Map();
  async open() {
    let tab;
    try {
      const list = await (await fetch("http://127.0.0.1:9223/json/list")).json();
      tab = list.find((t) => (t.url ?? "").includes("localhost:8080"));
    } catch { /* 녹화 밖에서 돌릴 때는 없습니다 */ }
    if (!tab) return null;
    this.#ws = new WebSocket(tab.webSocketDebuggerUrl);
    await new Promise((res, rej) => { this.#ws.onopen = res; this.#ws.onerror = rej; });
    this.#ws.onmessage = (e) => {
      const m = JSON.parse(e.data);
      if (m.id && this.#w.has(m.id)) { this.#w.get(m.id)(m.result); this.#w.delete(m.id); }
    };
    return this;
  }
  cmd(method, params = {}) {
    return new Promise((res) => {
      const id = ++this.#id; this.#w.set(id, res);
      this.#ws.send(JSON.stringify({ id, method, params }));
    });
  }
  async eval(expression) {
    const r = await this.cmd("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
    return r?.result?.value;
  }
  /** 한 글자씩. 한 번에 들어가면 붙여넣기처럼 보입니다. */
  async type(q) {
    for (let i = 1; i <= q.length; i++) {
      await this.eval(`(() => { const el = document.getElementById("q"); el.focus();
        el.value = ${JSON.stringify(q)}.slice(0, ${i});
        el.dispatchEvent(new Event("input", { bubbles: true })); })()`);
      await sleep(80 + Math.random() * 90);
    }
  }
  /** 검색해서 첫 결과를 담습니다. 담긴 곡 제목을 돌려줍니다. */
  async addFirst(q) {
    await this.type(q);
    await sleep(500);
    await this.eval(`document.getElementById("bSearch").click()`);
    for (let i = 0; i < 30; i++) {
      await sleep(300);
      if (await this.eval(`document.querySelectorAll("#results button").length`)) break;
    }
    await sleep(1200);
    const title = await this.eval(
      `(() => { const li = document.querySelector("#results li"); const b = li?.querySelector("button");
        const t = li?.querySelector(".ti")?.textContent ?? ""; b?.click(); return t; })()`);
    return title;
  }
  skip() { return this.eval(`document.getElementById("bSkip").click()`); }
  /** 화면을 그 자리로 굴립니다. 영상에서 '어디를 눌렀는지' 가 보여야 합니다. */
  async scrollTo(sel) {
    await this.eval(`document.querySelector(${JSON.stringify(sel)})
      ?.scrollIntoView({ behavior: "smooth", block: "center" })`);
    await sleep(1400);
  }
  async scrollTop() {
    await this.eval(`window.scrollTo({ top: 0, behavior: "smooth" })`);
    await sleep(1200);
  }

  /** '도착에 맞추기'. 버튼이 없으면(맞출 조합이 없으면) false 를 돌려줍니다. */
  fit() {
    return this.eval(`(() => { const b = [...document.querySelectorAll("#planSlot button")]
      .find((x) => x.textContent.includes("도착")); if (!b) return false; b.click(); return true; })()`);
  }
  /** 화면에 떠 있는 도착 맞춤 문장. 장면이 실제로 나왔는지 확인용입니다. */
  plan() { return this.eval(`document.querySelector("#planSlot b")?.textContent ?? ""`); }
  close() { this.#ws?.close(); }
}

const adb = (...a) => { try { execFileSync("adb", a, { stdio: "ignore" }); } catch {} };

/**
 * 잔여 ETA 주입.
 *
 * 도착 맞춤 편성은 내비 ETA 가 있어야 화면에 나타납니다. 에뮬레이터 기본 위치가
 * 국내가 아니어서 경로를 만들 수 없으므로, 차량 앱이 열어 둔 시연용 통로로
 * 남은 시간을 넣습니다 (PleosVehicleSignalSource.registerDemoEta).
 * 실차에서는 내비가 준 값이 이 주입값을 덮습니다. 음수면 해제입니다.
 */
const eta = (sec) => adb("shell", "am", "broadcast", "--user", "10",
  "-a", "dev.semicolon.playseat.DEMO_ETA", "--ei", "seconds", String(sec));

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

/**
 * 차량 화면에 뜬 탑승 코드를 읽습니다.
 *
 * 코드는 차 안 화면과 그 화면의 QR 에만 있고, 허브로는 나가지 않습니다. 사람은
 * QR 을 찍어서 받지만 흉내 폰에는 카메라가 없으므로 기기 로그에서 읽어 옵니다.
 * 못 읽으면 좌석 잡기가 전부 거절되니 여기서 바로 알려 줍니다.
 */
function readJoinCode() {
  try {
    const out = execFileSync("adb", ["logcat", "-d", "-s", "CarViewModel:I"], { encoding: "utf8" });
    const hits = [...out.matchAll(/탑승 코드 (\d{4})/g)];
    return hits.length ? hits[hits.length - 1][1] : null;
  } catch { return null; }
}

const code = readJoinCode();
if (code) console.log(`탑승 코드 ${code}`);
else console.warn("탑승 코드를 읽지 못했습니다 — 좌석 잡기가 거절될 수 있습니다");

/** 신호를 전부 초기 상태로. 시연은 늘 같은 자리에서 시작해야 합니다. */
async function reset() {
  eta(-1);
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
    phones.push(await new Phone({ ...p, joinCode: code }).connect());
    console.log(`   ${p.nickname} (${p.seatId}) 연결`);
    await wait(1.2);
  }
  // 2열 좌는 브라우저가 쓸 자리입니다. 좌석만 잡아 두고 바로 끊습니다 —
  // 같은 deviceId 로 둘이 붙으면 허브가 하나를 끊습니다.
  const seatBot = await new Phone({
    deviceId: "demo-r2l", seatId: "R2L", nickname: "지호", joinCode: code,
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
  await waitReal(7);
});

const seoa = () => phones.find((x) => x.nickname === "서아");
const mom = () => phones.find((x) => x.nickname === "엄마");

await scene("뒷자리 7살은 생각이 다릅니다", "뒷자리 7살은 생각이 다릅니다", async () => {
  // 차가 권한 것을 거절하고 자기 곡을 넣습니다. 차례가 없으니 바로 재생됩니다.
  seoa().dismissSuggestion();
  mark("beat", "서아 → 괜찮아요");
  console.log("   서아 → 괜찮아요 (추천 거절)");
  await wait(2.5);
  const t = await seoa().add("아기상어");
  mark("beat", `서아 → ${t?.title ?? "아기상어"}`);
  console.log(`   서아 → ${t ? `${t.title} — ${t.artist}` : "아기상어"} (바로 재생)`);
  await wait(4);
});

await scene("그리고 멈추지 않습니다", "한 사람이 연달아 넣어도 막지 않습니다", async () => {
  // 독점을 규칙으로 막지 않습니다. 같이 탄 사람들은 서로 보고 있고,
  // 그래도 불만이면 스킵 투표가 있습니다.
  // 같은 곡을 또 넣습니다. 기획 배경에 적은 "조카가 같은 동요를 열 번 틀어
  // 달라고 했다" 가 바로 이 장면입니다. 음원이 실제로 붙는 곡으로 고릅니다 —
  // 소리가 안 나는 곡을 고르면 '실제로 재생된다' 를 보여 주는 장면이 빕니다.
  for (const q of ["핑크퐁", "아기상어"]) {
    const t = await seoa().add(q);
    mark("beat", `서아 → ${t?.title ?? q}`);
    console.log(`   서아 → ${t ? `${t.title} — ${t.artist}` : q}`);
    await wait(2.5);
  }
});

const browser = await new BrowserPhone().open();
if (!browser) console.log("   (영상 속 폰이 없습니다 — 2열 좌 장면은 흉내 클라이언트 없이 지나갑니다)");

await scene("각자 폰에서, 차례 없이", "각자 폰에서 차례 없이 넣습니다", async () => {
  const t = await mom().add("폴킴");
  mark("beat", `엄마 → ${t?.title ?? "폴킴"}`);
  console.log(`   엄마 → ${t ? `${t.title} — ${t.artist}` : "폴킴"}`);
  await wait(3);
  if (browser) {
    mark("beat", "지호 → 검색어 입력");
    const title = await browser.addFirst("에스파");
    mark("beat", `지호 → ${title || "추가"}`);
    console.log(`   지호(영상 속 폰) → ${title || "(추가)"}`);
  }
  await wait(4);
});

await scene("거슬리면 투표", "거슬리면 투표로 건너뜁니다", async () => {
  // 혼자서는 못 넘깁니다. 과반(최소 2표)이 모여야 넘어갑니다.
  if (browser) await browser.skip(); else phones[0].skip();
  mark("beat", "지호 → 건너뛰기 1/2");
  console.log("   지호 → 건너뛰기 (1/2)");
  await wait(3);
  mom().skip();
  mark("beat", "엄마 → 건너뛰기 2/2 → 다음 곡");
  console.log("   엄마 → 건너뛰기 (2/2) → 다음 곡");
  await wait(4.5);
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

await scene("도착이 가까워집니다 — 마지막 곡이 잘리지 않게",
            "폰은 도착을 모릅니다. 차는 압니다", async () => {
  await car.steering(0);

  // 길이가 제각각인 곡이어야 '순서를 바꿔 맞춘다' 가 성립합니다.
  //
  // 미리듣기 음원은 전부 30초라서, 그것만 쌓으면 어떻게 바꿔도 끝나는 시각이
  // 같습니다. 앱은 "맞출 조합이 없다" 고 정직하게 답하지만 그러면 이 기능이
  // 화면에 나타나지 않습니다. 그래서 이 장면만 길이가 다른 곡으로 채웁니다.
  for (const [who, q] of [[mom(), "밤편지"], [seoa(), "바람기 메모리"],
                          [mom(), "벚꽃 엔딩"], [seoa(), "뽀로로"]]) {
    const t = await who.add(q);
    console.log(`   ${t ? `${t.title} — ${t.artist}` : q}`);
    await sleep(900);
  }
  await wait(2);

  // 남은 시간을 큐에서 거꾸로 계산해 넣습니다.
  //
  // 고정값을 넣으면 앞 장면에서 큐에 몇 곡이 남았느냐에 따라 도착선이 엉뚱한
  // 데 떨어지고, 그러면 '맞출 조합이 없습니다' 가 뜹니다. 그건 앱이 정직한
  // 것이지만 시연에서는 보여 줄 장면이 비어 버립니다. 그래서 "어느 곡에
  // 걸치게 할지" 를 정하고 거기에 맞는 ETA 를 역산합니다. 바꾸는 것은
  // 남은 시간 하나뿐이고, 판정은 전부 차가 합니다.
  // 계산은 흉내 폰이 받은 state 로 합니다. 브라우저 쪽에서 읽으면 스크롤·렌더
  // 타이밍에 걸려 가끔 빈 값이 나오고, 그러면 ETA 를 엉뚱하게 넣게 됩니다.
  const st = mom()?.state ?? {};
  const pm = (t) => t.playableMs || t.durationMs;
  let acc = st.nowPlaying ? Math.max(0, pm(st.nowPlaying) - (st.nowPlaying.positionMs || 0)) : 0;
  const lens = (st.queue ?? []).map(pm);
  const ends = lens.map((n) => (acc += n));
  let target = null;
  for (let k = 0; k < lens.length - 1; k++) {
    const gap = Math.min(...lens.slice(k + 1)) + 12000;   // 짧은 곡이 들어가고 조금 남는 틈
    if (lens[k + 1] > gap) { target = Math.round((ends[k] + gap) / 1000); break; }
  }
  if (!target) console.warn("   (도착선을 놓을 자리를 찾지 못했습니다 — 큐 구성을 보세요)");

  eta(target ?? 660);
  mark("beat", `잔여 ETA ${Math.round((target ?? 660) / 60)}분 주입`);
  console.log(`   도착까지 ${Math.round((target ?? 660) / 60)}분 → 큐 타임라인에 도착선이 그려집니다`);
  await wait(5);
  console.log(`   차량 판정: ${mom()?.state?.fitPlan ?? "(없음)"}`);

  if (browser) {
    // 폰에서도 타임라인이 보여야 합니다. 차 화면에만 있으면 '사람이 누른다' 는
    // 장면이 빕니다 — 이 기능의 요점은 차가 멋대로 바꾸지 않는다는 것입니다.
    await browser.scrollTo("#planSlot");
    await wait(2);
    console.log(`   폰 화면: ${await browser.plan() || "(비어 있음)"}`);
    const ok = await browser.fit();
    mark("beat", ok ? "지호 → 도착에 맞추기" : "맞출 조합이 없었습니다");
    console.log(ok ? "   지호 → '도착에 맞추기'" : "   (맞출 조합이 없어 버튼이 없습니다)");
    await wait(4);
    console.log(`   결과: ${mom()?.state?.fitPlan ?? "(없음)"}`);
    await browser.scrollTop();
  }
  console.log("   → 곡을 지우지 않고 순서만 바꿉니다. 마지막 곡이 도착과 함께 끝납니다");
  await wait(4);
});

await scene("도착 — 기어 P, 운전자 화면이 다시 열립니다", "정차하면 운전자 화면이 다시 열린다", async () => {
  eta(-1);
  await car.gear("Park");
  console.log("   → 전체 큐·검색·좌석 현황이 돌아옵니다");
  await wait(4);
  mark("beat", "좌석 지분이 드러납니다");
  console.log("   → 좌석별 선곡 지분이 띠로 보입니다. 막지 않고 보여 주기만 합니다");
  await wait(5);
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
  browser?.close();
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
