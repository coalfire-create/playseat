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

  constructor({ host = "localhost:8080", session = "a3f9", deviceId, seatId, nickname, joinCode = null }) {
    Object.assign(this, { host, session, deviceId, seatId, nickname, joinCode });
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
    // 탑승 코드는 차 안 화면에만 있습니다. 흉내 폰은 시연 스크립트가 로그에서
    // 읽어 쥐여 줍니다 — 사람이 QR 을 찍어 받는 것과 같은 값입니다.
    this.send({
      type: "seat.claim", deviceId: this.deviceId, seatId: this.seatId,
      nickname: this.nickname, joinCode: this.joinCode,
    });
    return this;
  }

  send(o) { this.#ws.send(JSON.stringify({ ts: Date.now(), ...o })); }
  close() { this.#ws?.close(); }

  search(q, limit = 5) {
    return new Promise((res) => { this.#queue.push(res); this.send({ type: "search.query", q, limit }); });
  }

  /** 검색해서 첫 곡을 큐에 넣습니다. */
  async add(q) {
    const items = await this.search(q, 3);
    const t = items[0];
    if (!t) return null;
    this.send({
      type: "queue.add", seatId: this.seatId, title: t.title, artist: t.artist,
      durationMs: t.durationMs, externalId: t.externalId, artworkUrl: t.artworkUrl,
      // 음원 주소와 실제로 흐르는 길이를 같이 넘깁니다. 이게 빠지면 흉내 폰이
      // 넣은 곡만 소리가 안 나고, 도착 맞춤도 3분짜리 곡으로 계산합니다.
      previewUrl: t.previewUrl, playableMs: t.playableMs,
    });
    return t;
  }

  /** 도착에 맞춰 순서 바꾸기. */
  fit() { this.send({ type: "queue.fit", seatId: this.seatId }); }

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
