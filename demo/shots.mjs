#!/usr/bin/env node
/**
 * 화면 시안 캡처.
 *
 * 접수양식의 '앱 화면 시안 (차량 디스플레이 + 동승자 폰 화면)' 을 만듭니다.
 * 손으로 찍으면 상태가 매번 달라지고, 다시 찍을 때 같은 장면이 안 나옵니다.
 * 신호를 주입해 장면을 만들고 같은 자리에서 찍습니다.
 *
 * 준비 — scenario.mjs 와 같습니다 (에뮬레이터 + 차량 앱 + adb forward + 허브).
 *
 *   node demo/shots.mjs                 # docs/screens/ 에 저장
 *   node demo/shots.mjs --out /tmp/x    # 다른 폴더로
 *   node demo/shots.mjs --no-phone      # 차량 화면만 (Chrome 없이)
 *
 * 폰 화면은 헤드리스 Chrome 을 CDP 로 직접 몰아 390x844(2x) 로 찍습니다.
 * 확장이나 puppeteer 를 쓰지 않습니다 — 설치 없이 돌아야 하기 때문입니다.
 */

import { Console, sleep } from "./console.mjs";
import { Phone, CAST } from "./phones.mjs";
import { execFileSync, spawn } from "node:child_process";
import { mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const args = process.argv.slice(2);
const argOf = (k, d) => { const i = args.indexOf(`--${k}`); return i >= 0 ? args[i + 1] : d; };
const OUT = argOf("out", "docs/screens");
const WITH_PHONE = !args.includes("--no-phone");
const HUB = argOf("hub", "localhost:8080");

mkdirSync(OUT, { recursive: true });

/* ---------- 차량 화면 ---------- */

// Pleos 에뮬레이터는 2560x1440 이고 위아래에 시스템 바가 있습니다.
// 앱 영역만 잘라야 '화면 시안' 으로 쓸 수 있습니다.
const CAR_CROP = [856, 88, 2544, 1298];

const adb = (...a) => { try { execFileSync("adb", a, { stdio: "ignore" }); } catch {} };

/** 화면이 꺼져 있거나 앱이 뒤로 가 있으면 까만 화면이 찍힙니다. */
function wakeCar() {
  adb("shell", "svc", "power", "stayon", "true");
  adb("shell", "am", "start", "--user", "10",
      "-n", "dev.semicolon.playseat.car/.MainActivity");
}

/**
 * 세션을 새로 시작합니다.
 *
 * 세션 상태(좌석·큐·추천 스누즈)는 차량 앱이 들고 있습니다. 앞선 실행에서
 * 추천을 거절했다면 10분 동안 다시 안 뜨므로, 같은 장면이 안 나옵니다.
 */
function restartCarApp() {
  adb("shell", "am", "force-stop", "--user", "10", "dev.semicolon.playseat.car");
  wakeCar();
}

function carShot(name) {
  let png = execFileSync("adb", ["exec-out", "screencap", "-p"], {
    maxBuffer: 64 * 1024 * 1024, encoding: "buffer",
  });
  // 까만 화면은 PNG 가 아주 작게 나옵니다. 한 번은 깨워서 다시 찍습니다.
  if (png.length < 60_000) {
    wakeCar();
    png = execFileSync("adb", ["exec-out", "screencap", "-p"],
      { maxBuffer: 64 * 1024 * 1024, encoding: "buffer" });
    if (png.length < 60_000) console.log("   (화면이 까맣습니다 — 에뮬레이터를 확인하세요)");
  }
  const raw = join(OUT, `${name}.png`);
  writeFileSync(raw, png);
  try {
    execFileSync("python3", ["-c", `
from PIL import Image
im = Image.open(${JSON.stringify(raw)})
im.crop(${JSON.stringify(CAR_CROP)}).save(${JSON.stringify(raw)})
`]);
  } catch {
    console.log(`   (자르기 생략 — python3 + Pillow 가 없으면 전체 화면으로 남습니다)`);
  }
  console.log("   차량", raw);
}

/* ---------- 폰 화면 ---------- */

class Cdp {
  #ws; #id = 0; #waiters = new Map(); #chrome; #profile;

  async open() {
    this.#profile = join(tmpdir(), `playseat-shots-${process.pid}`);
    const bin = process.platform === "darwin"
      ? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
      : "google-chrome";
    this.#chrome = spawn(bin, [
      "--headless=new", "--remote-debugging-port=9222", "--no-first-run",
      `--user-data-dir=${this.#profile}`, "about:blank",
    ], { stdio: "ignore" });

    for (let i = 0; i < 40; i++) {
      try { await fetch("http://127.0.0.1:9222/json/version"); break; } catch { await sleep(500); }
    }
    const tab = await (await fetch(
      "http://127.0.0.1:9222/json/new?about:blank", { method: "PUT" })).json();
    this.tabId = tab.id;
    this.#ws = new WebSocket(tab.webSocketDebuggerUrl);
    await new Promise((r) => (this.#ws.onopen = r));
    this.#ws.onmessage = (e) => {
      const m = JSON.parse(e.data);
      if (m.id && this.#waiters.has(m.id)) { this.#waiters.get(m.id)(m.result); this.#waiters.delete(m.id); }
    };
    await this.cmd("Page.enable");
    await this.cmd("Runtime.enable");
    await this.cmd("Emulation.setDeviceMetricsOverride",
      { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
    // 헤드리스 기본값에 기대지 않습니다. 주간으로 고정해 두고 야간 장면에서만 바꿉니다.
    await this.dark(false);
    return this;
  }

  cmd(method, params = {}) {
    return new Promise((res) => {
      const id = ++this.#id; this.#waiters.set(id, res);
      this.#ws.send(JSON.stringify({ id, method, params }));
    });
  }

  dark(on) {
    return this.cmd("Emulation.setEmulatedMedia",
      { features: [{ name: "prefers-color-scheme", value: on ? "dark" : "light" }] });
  }

  js(expression) { return this.cmd("Runtime.evaluate", { expression, awaitPromise: true }); }

  async shot(name) {
    const r = await this.cmd("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
    const p = join(OUT, `${name}.png`);
    writeFileSync(p, Buffer.from(r.data, "base64"));
    console.log("   폰  ", p);
  }

  async close() {
    try { await fetch(`http://127.0.0.1:9222/json/close/${this.tabId}`); } catch {}
    this.#ws?.close();
    this.#chrome?.kill();
    try { rmSync(this.#profile, { recursive: true, force: true }); } catch {}
  }
}

/* ---------- 장면 ---------- */

restartCarApp();
await sleep(6000);
const car = await new Console().open();
const phone = WITH_PHONE ? await new Cdp().open() : null;
const phones = [];

const night = (on) => execFileSync("adb", ["shell", "cmd", "uimode", "night", on ? "yes" : "no"]);

/**
 * 폰 화면을 띄웁니다.
 *
 * 브라우저가 쓰는 deviceId(`demo-r2l`)는 스크립트 쪽 폰들과 **겹치면 안 됩니다**.
 * 같은 id 로 두 군데가 붙으면 허브가 앞의 연결을 끊고, 끊긴 쪽은 응답을
 * 영원히 기다립니다. 그래서 2열 좌는 브라우저 전용으로 비워 둡니다.
 */
async function openPhone({ claimed }) {
  if (!phone) return;
  await phone.js(`localStorage.setItem("playseat.nickname","지호");` +
    (claimed ? `localStorage.setItem("playseat.deviceId","demo-r2l")`
             : `localStorage.removeItem("playseat.deviceId")`));
  await phone.cmd("Page.navigate", { url: `http://${HUB}/?s=a3f9` });
  await sleep(2500);
}

console.log("1. 정차 — 좌석도와 QR (아직 아무도 폰을 붙이지 않음)");
await car.gear("Park"); await car.wiper("Off"); await car.headlamp("Off");
await car.steering(0);
// 충전은 읽어서 확인합니다. 이전 실행이 충전 상태로 끝났으면
// 첫 장면이 '충전 라운지' 로 시작해 버립니다.
for (let i = 0; i < 6; i++) {
  await car.charging(false);
  await sleep(1200);
  if ((await car.state()).ev_battery?.charging_activated === false) break;
}
await car.seats({ R1L: true, R1R: true, R2L: true, R2R: true });
await sleep(2500);
carShot("01_차량_정차_좌석도");
await openPhone({ claimed: false });
await phone?.shot("02_폰_좌석선택");

// 선곡 순서는 운전석부터 돕니다. 운전석은 폰으로 곡을 넣지 않으므로
// 첫 차례 동안 큐가 비어 있고, 바로 거기서 '차가 먼저 권하는' 장면이 나옵니다.
console.log("2. 추천 — 아무도 안 넣을 때");
for (const p of CAST) phones.push(await new Phone(p).connect());

// 2열 좌는 브라우저가 쓸 자리입니다. 좌석만 먼저 잡아 두고 바로 끊습니다 —
// 좌석-기기 연결은 차량이 들고 있으므로, 같은 deviceId 로 브라우저가 열면
// 그대로 '지호' 로 인식됩니다. 둘이 동시에 붙어 있으면 허브가 하나를 끊습니다.
const seatBot = await new Phone({
  deviceId: "demo-r2l", seatId: "R2L", nickname: "지호",
}).connect();
await sleep(1500);
seatBot.close();

for (let i = 0; i < 40; i++) {
  if ((phones[0].state?.suggestions ?? []).length) break;
  await sleep(1000);
}
if (!(phones[0].state?.suggestions ?? []).length) console.log("   (추천이 뜨지 않았습니다)");
carShot("03_차량_추천");
await openPhone({ claimed: true });
await phone?.shot("04_폰_추천");
// 거절해 두면 10분 동안 다시 권하지 않습니다 — 이후 장면이 깨끗해집니다.
phones.forEach((p) => p.dismissSuggestion());
await sleep(2000);

console.log("3. 좌석 턴 — 사람이 고른 곡");
for (const [who, q] of [["엄마", "아이유"], ["서아", "아기상어"]]) {
  const p = phones.find((x) => x.nickname === who);
  for (let i = 0; i < 40 && p.state?.turnSeatId !== p.seatId; i++) await sleep(1000);
  await p.add(q);
  await sleep(1500);
}
await sleep(2000);
carShot("05_차량_선곡_대기열");
await openPhone({ claimed: true });
await phone?.shot("06_폰_메인");

console.log("4. 주행 — 운전자 화면 축약");
await car.gear("Drive");
await sleep(4000);
carShot("07_차량_주행_축약");
await openPhone({ claimed: true });
await phone?.shot("08_폰_주행_권한");

console.log("5. 비 — 분위기와 조명");
await car.wiper("Low");
await sleep(5000);
carShot("09_차량_주행_비");
await openPhone({ claimed: true });
await phone?.shot("10_폰_비");

console.log("6. 충전 라운지");
await car.wiper("Off");
await car.gear("Park");
await sleep(2000);
await car.charging(true);
await sleep(4000);
carShot("11_차량_충전라운지");

console.log("7. 야간");
await car.charging(false);
night(true);
await sleep(5000);
carShot("12_차량_야간");
if (phone) {
  await phone.dark(true);
  await openPhone({ claimed: true });
  await phone.shot("13_폰_야간");
  await phone.dark(false);
}
night(false);
await sleep(3000);

console.log("정리");
phones.forEach((p) => p.close());
await car.seats({ R1L: false, R1R: false, R2L: false, R2R: false });
await car.gear("Park");
car.close();
await phone?.close();
console.log(`\n${OUT} 에 저장했습니다.`);
process.exit(0);
