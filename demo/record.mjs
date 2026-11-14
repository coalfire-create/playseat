#!/usr/bin/env node
/**
 * 시연 녹화.
 *
 * 차량 화면은 에뮬레이터의 `screenrecord` 로, 동승자 폰 화면은 헤드리스 Chrome 을
 * CDP 로 몰면서 프레임으로 받습니다. 그동안 `scenario.mjs` 가 신호를 넣습니다.
 * 세 가지가 같은 시각 기준을 공유해야 나중에 붙일 수 있으므로 한 프로세스가 묶습니다.
 *
 *   node demo/record.mjs                 # ./recording 에 저장
 *   node demo/record.mjs --out /tmp/rec  # 다른 폴더로
 *   node demo/record.mjs --no-phone      # 차량 화면만
 *
 * 결과
 *   car.mp4        차량 디스플레이 원본 (에뮬레이터 전체 화면)
 *   phone/*.png    폰 화면 프레임
 *   frames.json    폰 프레임의 시각 (녹화 시작 기준 초)
 *   cues.json      장면이 바뀐 시각 (scenario.mjs 가 기록)
 *   meta.json      오프셋과 자르기 좌표
 *
 * 이어서 `node demo/compose.mjs` 가 자막을 얹어 한 편으로 만듭니다.
 */

import { spawn, execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const args = process.argv.slice(2);
const argOf = (k, d) => { const i = args.indexOf(`--${k}`); return i >= 0 ? args[i + 1] : d; };
const OUT = argOf("out", "recording");
const WITH_PHONE = !args.includes("--no-phone");
const HUB = argOf("hub", "localhost:8080");

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(join(OUT, "phone"), { recursive: true });

/**
 * 앞선 실행에서 남은 헤드리스 Chrome 정리.
 *
 * 같은 좌석(deviceId)으로 두 군데가 붙으면 허브가 번갈아 끊어서, 폰 화면이
 * 2초마다 '재연결 중' 으로 깜빡입니다. 영상에 그대로 찍힙니다.
 */
for (const port of [9222, 9223]) {
  try {
    const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
    for (const t of list) {
      if ((t.url ?? "").includes(HUB)) {
        await fetch(`http://127.0.0.1:${port}/json/close/${t.id}`);
        console.log(`남아 있던 폰 화면을 닫았습니다 (포트 ${port})`);
      }
    }
  } catch { /* 안 떠 있으면 그만입니다 */ }
}

/** 에뮬레이터 전체 화면 중 앱이 차지하는 영역. */
const CAR_CROP = { x: 856, y: 88, w: 1688, h: 1210 };
const DEVICE = "/sdcard/playseat-car.mp4";

/* ---------- 폰 화면 ---------- */

class PhoneCam {
  #ws; #id = 0; #waiters = new Map(); #chrome; #profile; #stop = false;
  frames = [];

  async open() {
    this.#profile = join(tmpdir(), `playseat-rec-${process.pid}`);
    const bin = process.platform === "darwin"
      ? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
      : "google-chrome";
    this.#chrome = spawn(bin, [
      "--headless=new", "--remote-debugging-port=9223", "--no-first-run",
      `--user-data-dir=${this.#profile}`, "about:blank",
    ], { stdio: "ignore" });

    for (let i = 0; i < 40; i++) {
      try { await fetch("http://127.0.0.1:9223/json/version"); break; } catch { await sleep(500); }
    }
    const tab = await (await fetch(
      "http://127.0.0.1:9223/json/new?about:blank", { method: "PUT" })).json();
    this.tabId = tab.id;
    this.#ws = new WebSocket(tab.webSocketDebuggerUrl);
    await new Promise((r) => (this.#ws.onopen = r));
    this.#ws.onmessage = (e) => {
      const m = JSON.parse(e.data);
      if (m.id && this.#waiters.has(m.id)) { this.#waiters.get(m.id)(m.result); this.#waiters.delete(m.id); }
      else if (m.method === "Page.screencastFrame") this.onFrame?.(m.params);
    };
    await this.cmd("Page.enable");
    await this.cmd("Emulation.setDeviceMetricsOverride",
      // 화면 스트림은 1배율로만 나옵니다. 두 배 크기 화면에 페이지를 두 배로
      // 키워 열면(`?zoom=2`) 배치는 390×844 그대로이고 글자는 선명하게 받습니다.
      { width: 780, height: 1688, deviceScaleFactor: 1, mobile: true });
    await this.cmd("Emulation.setEmulatedMedia",
      { features: [{ name: "prefers-color-scheme", value: "light" }] });
    // 좌석은 scenario.mjs 가 미리 잡아 둡니다. 여기서는 그 자리로 들어가기만 합니다.
    //
    // localStorage 는 **오리진별**입니다. about:blank 에서 써 두면 페이지가
    // 못 읽습니다 — 먼저 페이지를 열고, 거기서 쓰고, 다시 읽습니다.
    await this.cmd("Page.navigate", { url: `http://${HUB}/?s=a3f9&zoom=2` });
    await sleep(2000);
    await this.cmd("Runtime.evaluate", { expression:
      `localStorage.setItem("playseat.nickname","지호");` +
      `localStorage.setItem("playseat.deviceId","demo-r2l")` });
    return this;
  }

  cmd(method, params = {}) {
    return new Promise((res) => {
      const id = ++this.#id; this.#waiters.set(id, res);
      this.#ws.send(JSON.stringify({ id, method, params }));
    });
  }

  /**
   * 화면이 바뀔 때마다 한 장씩 받고, 받은 시각을 같이 남깁니다(가변 프레임레이트).
   *
   * 예전에는 `Page.captureScreenshot` 을 되풀이해 불렀습니다. 그런데 화면에
   * 전환 효과가 돌고 있으면 그 호출이 몇 초씩, 때로는 끝내 돌아오지 않았고,
   * 그 사이의 폰 화면이 통째로 비었습니다. 스크린캐스트는 묻지 않아도
   * 렌더러가 프레임을 밀어 주므로 막힐 호출이 없고, 전환도 끊기지 않고 찍힙니다.
   */
  async run(t0) {
    let i = 0;
    this.onFrame = (p) => {
      const t = (Date.now() - t0) / 1000;
      const name = `f${String(i++).padStart(5, "0")}.jpg`;
      writeFileSync(join(OUT, "phone", name), Buffer.from(p.data, "base64"));
      this.frames.push({ t: Math.round(t * 1000) / 1000, f: name });
      this.cmd("Page.screencastFrameAck", { sessionId: p.sessionId });
    };
    await this.cmd("Page.startScreencast", { format: "jpeg", quality: 90, everyNthFrame: 2 });
    // 가만히 있는 화면에서는 첫 장이 오지 않습니다. 한 번 흔들어 첫 장을 받습니다.
    await this.cmd("Runtime.evaluate", { expression:
      `window.scrollTo(0,1); requestAnimationFrame(() => window.scrollTo(0,0))` });
    while (!this.#stop) await sleep(200);
    await Promise.race([this.cmd("Page.stopScreencast"), sleep(1000)]);
  }

  async close() {
    this.#stop = true;
    await sleep(400);
    try { await fetch(`http://127.0.0.1:9223/json/close/${this.tabId}`); } catch {}
    this.#ws?.close();
    this.#chrome?.kill();
    try { rmSync(this.#profile, { recursive: true, force: true }); } catch {}
  }
}

/* ---------- 녹화 ---------- */

const adb = (...a) => execFileSync("adb", a, { encoding: "utf8" });

// 세션을 새로 시작합니다. 앱이 뜨는 장면은 영상에 넣지 않으므로 녹화 전에 합니다.
console.log("차량 앱을 다시 시작합니다…");
try {
  adb("shell", "am", "force-stop", "--user", "10", "dev.semicolon.playseat.car");
  adb("shell", "svc", "power", "stayon", "true");
  adb("shell", "am", "start", "--user", "10",
      "-n", "dev.semicolon.playseat.car/.MainActivity");
} catch {}
await sleep(7000);

try { adb("shell", "rm", "-f", DEVICE); } catch {}
console.log("차량 화면 녹화를 시작합니다…");
const rec = spawn("adb", ["shell", "screenrecord", "--time-limit", "0",
  "--bit-rate", "16M", DEVICE], { stdio: "ignore" });

// 인코더가 실제로 물리기까지 한 박자 걸립니다. 그 뒤를 시각 0 으로 잡습니다.
await sleep(2500);
const t0 = Date.now();

const cam = WITH_PHONE ? await new PhoneCam().open() : null;

/** 좌석이 잡히면 폰 화면을 한 번 새로고침해 '내 자리' 로 들어갑니다. */
async function joinWhenSeated() {
  if (!cam) return;
  for (let i = 0; i < 90; i++) {
    await sleep(1000);
    const r = await cam.cmd("Runtime.evaluate", {
      expression: `(state?.seats||[]).some(s=>s.deviceId==="demo-r2l")`,
      returnByValue: true,
    });
    if (r?.result?.value) {
      await cam.cmd("Page.reload", {});
      return;
    }
  }
}

const camRun = cam ? cam.run(t0) : null;
const joining = joinWhenSeated();

console.log("시나리오를 시작합니다.\n");
const cuePath = join(OUT, "cues.json");
const scenarioStart = (Date.now() - t0) / 1000;
await new Promise((res, rej) => {
  const p = spawn("node", ["demo/scenario.mjs", "--log", cuePath, "--no-restart"],
    { stdio: "inherit" });
  p.on("exit", (code) => (code === 0 ? res() : rej(new Error(`scenario exit ${code}`))));
});

await joining.catch(() => {});
console.log("\n녹화를 마칩니다…");
await sleep(2000);
try { adb("shell", "pkill", "-INT", "screenrecord"); } catch {}
await sleep(2500);
rec.kill();
await cam?.close();
await camRun?.catch(() => {});

adb("pull", DEVICE, join(OUT, "car.mp4"));
try { adb("shell", "rm", "-f", DEVICE); } catch {}

writeFileSync(join(OUT, "frames.json"), JSON.stringify(cam?.frames ?? [], null, 1));
writeFileSync(join(OUT, "meta.json"), JSON.stringify({
  t0, scenarioStart, carCrop: CAR_CROP,
  phoneFrames: cam?.frames.length ?? 0,
}, null, 1));

const cues = existsSync(cuePath) ? JSON.parse(readFileSync(cuePath, "utf8")).cues : [];
console.log(`\n차량 영상 : ${join(OUT, "car.mp4")}`);
console.log(`폰 프레임 : ${cam?.frames.length ?? 0}장`);
console.log(`장면      : ${cues.length}개`);
console.log(`\n다음: node demo/compose.mjs --in ${OUT}`);
process.exit(0);
