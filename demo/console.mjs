#!/usr/bin/env node
/**
 * Pleos Emulator Virtual Console 클라이언트.
 *
 * 에뮬레이터 안에서 도는 Virtual Console 의 HTTP API 를 그대로 씁니다.
 * 브라우저로 콘솔 화면을 열어 손으로 누르는 대신, 시연 시나리오가
 * 같은 신호를 순서대로 주입할 수 있게 하는 것이 목적입니다.
 *
 * 쓰기 전에 포트를 넘겨 두어야 합니다.
 *
 *   adb forward tcp:8088 tcp:8088
 *
 * 왜 하트비트가 필요한가
 *   콘솔은 "브라우저가 보고 있는 동안"에만 신호 주입을 받습니다
 *   (`POST /browser/session`). 1초마다 두드리지 않으면 3초 뒤
 *   `emulator_inactive` 로 거절합니다. 그래서 주입과 하트비트를
 *   같은 프로세스에 둡니다 — 따로 두면 한쪽만 죽어도 조용히 안 먹습니다.
 *
 * 단독 실행하면 현재 상태를 덤프합니다.
 *
 *   node demo/console.mjs            # 지금 신호 상태
 *   node demo/console.mjs --probe    # 주입 가능한 항목 확인
 */

const BASE = process.env.PLEOS_CONSOLE ?? "http://127.0.0.1:8088";

/** 좌석 식별자 — 차량 앱의 SeatId 와 Virtual Console 의 이름 대응. */
export const SEAT = {
  R1L: "driver",
  R1R: "passenger",
  R2L: "rear_left",
  R2R: "rear_right",
  // 2열 가운데는 Virtual Console 에 항목이 없습니다. 차량 앱에서는
  // 늘 빈 좌석으로 나오며, 이는 에뮬레이터의 한계입니다(실차는 제공).
};

export class Console {
  #timer = null;
  #client = `playseat-${process.pid}`;

  /**
   * 하트비트 시작. 이걸 부르기 전의 쓰기는 전부 거절됩니다.
   *
   * 하트비트가 붙은 뒤에도 콘솔이 DDS 로 실제 차량에 쏘기까지 한 박자
   * 걸립니다(`availability.available`). 그 사이에 넣은 신호는 콘솔 안에만
   * 남고 앱에는 가지 않습니다 — 조용히 틀리는 종류라서 여기서 기다립니다.
   */
  async open({ timeoutMs = 15000 } = {}) {
    await this.#beat();
    this.#timer = setInterval(() => this.#beat().catch(() => {}), 1000);
    this.#timer.unref?.();

    const until = Date.now() + timeoutMs;
    while (Date.now() < until) {
      const s = await this.state().catch(() => null);
      if (s?.availability?.available) {
        // 'available' 이 뜬 직후 첫 신호는 간혹 묻힙니다. 한 박자 둡니다 —
        // 묻히면 시연 첫 장면만 조용히 틀린 상태로 시작합니다.
        await new Promise((r) => setTimeout(r, 1500));
        return this;
      }
      await new Promise((r) => setTimeout(r, 500));
    }
    throw new Error(
      "Virtual Console 이 차량에 신호를 쏠 수 없는 상태입니다(availability=false).\n" +
      "  · adb forward tcp:8088 tcp:8088 이 걸려 있는지\n" +
      "  · 에뮬레이터가 완전히 부팅됐는지 확인하세요.",
    );
  }

  close() {
    if (this.#timer) clearInterval(this.#timer);
    this.#timer = null;
  }

  #beat() {
    return fetch(`${BASE}/browser/session?client=${this.#client}`, { method: "POST" });
  }

  async state() {
    const r = await fetch(`${BASE}/vehicle/state`);
    if (!r.ok) throw new Error(`state ${r.status}`);
    return r.json();
  }

  /**
   * 쓰기. 콘솔은 **쿼리 파라미터**로 받습니다(본문 JSON 아님).
   * 항목별 파라미터 이름은 `GET /vehicle/catalog` 의 `control` 에 적혀 있습니다.
   */
  async put(path, params) {
    const q = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) q.set(k, String(v));
    const url = `${BASE}${path}?${q}`;
    // 콘솔이 잠깐 `emulator_inactive`(503) 를 돌려줄 때가 있습니다. 하트비트가
    // 한 번 늦었을 뿐이라 조금 기다리면 풀립니다. 여기서 바로 던지면 3분짜리
    // 녹화가 중간에 통째로 죽습니다.
    let r, text;
    for (let i = 0; i < 12; i++) {
      r = await fetch(url, { method: "PUT" });
      text = await r.text();
      if (r.status !== 503) break;
      await new Promise((res) => setTimeout(res, 500));
    }
    if (!r.ok) throw new Error(`PUT ${path}?${q} → ${r.status} ${text.slice(0, 120)}`);
    return text;
  }

  /* ---- 시나리오가 쓰는 신호들 ---- */

  /** 기어. "Park" | "Reverse" | "Neutral" | "Drive" — 차량 앱의 주행/정차 판정. */
  gear(value) { return this.put("/vehicle/gear", { gear: value }); }

  /** 좌석 착석. `{ R1L: true, R2R: true }` 처럼 부분만 넘겨도 됩니다. */
  async seats(bySeatId) {
    for (const [id, on] of Object.entries(bySeatId)) {
      const area = SEAT[id];
      if (!area) continue;   // 2열 가운데는 콘솔에 항목이 없습니다
      await this.put("/vehicle/seat", { kind: "occupied", area, value: !!on });
    }
  }

  /** 앞유리 와이퍼. "Off" | "Auto" | "Low" | "High" — 비 판정의 근거입니다. */
  wiper(mode) { return this.put("/vehicle/body", { front_wiper: mode }); }

  /** 전조등. "Off" | "Auto" | "Position" | "LowBeam" — 어둠 판정의 근거입니다. */
  headlamp(mode) {
    return this.put("/vehicle/lights", { feature: "headlamp_mode", value: mode });
  }

  /** 조향각(도, -470~500). 굽잇길 판정은 최근 12초 변동폭을 봅니다. */
  steering(deg) { return this.put("/vehicle/controls", { steering: deg }); }

  /**
   * 충전. 켜면 차량 앱이 충전 라운지 모드로 들어갑니다.
   *
   * 충전구(포트)와 충전 스위치를 같이 움직입니다. 차량 앱은 포트 열림
   * 이벤트를 받고 나서 스위치 상태를 다시 읽기 때문에, 스위치만 바꾸면
   * 상태는 맞는데 앱이 모르는 상태가 됩니다. 실제로도 꽂아야 켜집니다.
   */
  async charging(on) {
    await this.put("/vehicle/ev-battery", { field: "charge_switch", value: !!on });
    await this.put("/vehicle/ev-battery", { field: "charge_port_open", value: !!on });
  }
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ---- 단독 실행 ----
   경로에 한글이 섞이면 import.meta.url 은 퍼센트 인코딩되고 argv[1] 은 안 되므로
   문자열 비교가 조용히 어긋납니다. 실제 경로로 비교합니다. */
import { realpathSync } from "node:fs";
import { fileURLToPath } from "node:url";
const runDirectly = (() => {
  try { return realpathSync(fileURLToPath(import.meta.url)) === realpathSync(process.argv[1]); }
  catch { return false; }
})();

if (runDirectly) {
  const c = await new Console().open();
  const s = await c.state();
  console.log("기어     ", s.gear?.actual?.value);
  console.log("착석     ", JSON.stringify(s.seat?.occupied));
  console.log("와이퍼   ", s.body?.wipers?.front?.mode);
  console.log("전조등   ", s.lights?.headlamp_mode);
  console.log("속도/조향", s.controls?.speed_kph, "/", s.controls?.steering_deg);
  console.log("충전     ", s.ev_battery?.charging_activated);

  // 손으로 Virtual Console 화면을 조작하며 시연할 때, 이 프로세스를 띄워 두면
  // 브라우저를 열지 않아도 신호 주입이 살아 있습니다.
  if (process.argv.includes("--hold")) {
    console.log("\n세션 유지 중. Ctrl+C 로 종료합니다.");
    // 최상위 await 로 붙잡으면 Node 가 '끝나지 않는 await' 로 보고 13번으로 죽습니다.
    // 타이머로 붙잡고, 아래 c.close() 에 닿지 않게 여기서 끝냅니다 —
    // close() 가 하트비트를 끄면 세션이 조용히 죽습니다.
    setInterval(() => {}, 1 << 30);
    process.on("SIGINT", () => { c.close(); process.exit(0); });
  } else if (process.argv.includes("--probe")) {

    const checks = [
      ["기어",   () => c.gear("Drive")],
      ["착석",   () => c.seats({ R1L: true })],
      ["와이퍼", () => c.wiper("Low")],
      ["전조등", () => c.headlamp("LowBeam")],
      ["조향",   () => c.steering(30)],
      ["충전",   () => c.charging(true)],
    ];
    for (const [name, fn] of checks) {
      try { await fn(); console.log("  OK  ", name); }
      catch (e) { console.log("  실패", name, "—", e.message); }
    }
    // 건드린 것은 되돌립니다. 확인하려고 돌린 스크립트가
    // 차를 주행 상태로 남겨 두면 다음 사람이 헷갈립니다.
    await c.gear("Park"); await c.seats({ R1L: false });
    await c.wiper("Off"); await c.steering(0); await c.charging(false);
    await c.headlamp("Off");
    c.close();
  } else {
    c.close();
  }
}
