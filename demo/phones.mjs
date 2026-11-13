#!/usr/bin/env node
/**
 * 동승자 폰 흉내.
 *
 * 시연할 때마다 폰 네 대를 들고 QR 을 찍을 수는 없습니다. 이 모듈은
 * `contract/protocol.md` 의 폰 쪽 역할만 그대로 수행합니다 —
 * 좌석을 잡고, 자기 차례에 곡을 넣고, 투표합니다.
 *
 * 화면이 필요한 한 자리(보통 2열 좌)는 비워 두고 진짜 브라우저로 붙이면
 * 영상에 '사람이 쓰는 폰 화면'이 그대로 들어갑니다.
 */

export class Phone {
  #ws; #queue = []; state = null;

  constructor({ host = "localhost:8080", session = "a3f9", deviceId, seatId, nickname }) {
    Object.assign(this, { host, session, deviceId, seatId, nickname });
  }

  async connect() {
    this.#ws = new WebSocket(
      `ws://${this.host}/session/${this.session}?role=phone&deviceId=${this.deviceId}`,
    );
    await new Promise((res, rej) => {
      this.#ws.onopen = res;
      this.#ws.onerror = () => rej(new Error(`허브에 붙지 못했습니다 (${this.host})`));
    });
    this.#ws.onmessage = (e) => {
      const m = JSON.parse(e.data);
      if (m.type === "state.sync") this.state = m;
      if (m.type === "search.result") this.#queue.shift()?.(m.items ?? []);
    };
    this.send({ type: "seat.claim", deviceId: this.deviceId, seatId: this.seatId, nickname: this.nickname });
    return this;
  }

  send(o) { this.#ws.send(JSON.stringify({ ts: Date.now(), ...o })); }
  close() { this.#ws?.close(); }

  search(q, limit = 5) {
    return new Promise((res) => { this.#queue.push(res); this.send({ type: "search.query", q, limit }); });
  }

  /** 검색해서 첫 곡을 큐에 넣습니다. 차례가 아니면 차량이 거절합니다 — 그대로 둡니다. */
  async add(q) {
    const items = await this.search(q, 3);
    const t = items[0];
    if (!t) return null;
    this.send({
      type: "queue.add", seatId: this.seatId, title: t.title, artist: t.artist,
      durationMs: t.durationMs, externalId: t.externalId, artworkUrl: t.artworkUrl,
    });
    return t;
  }

  like() { this.send({ type: "reaction.like", seatId: this.seatId }); }
  skip() { this.send({ type: "vote.skip", seatId: this.seatId }); }
  dismissSuggestion() { this.send({ type: "mood.dismiss", seatId: this.seatId }); }
  acceptSuggestion() { this.send({ type: "mood.accept", seatId: this.seatId }); }
}

/** 시연 기본 승객. 2열 좌는 비워 둡니다 — 진짜 폰 브라우저로 붙일 자리입니다. */
export const CAST = [
  { deviceId: "demo-r1r", seatId: "R1R", nickname: "엄마" },
  { deviceId: "demo-r2r", seatId: "R2R", nickname: "서아" },
];
