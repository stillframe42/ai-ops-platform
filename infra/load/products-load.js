// 상시 기본 트래픽 생성기 (docs/scenarios.md — 비율 기반 트리거의 최소 표본 조건 충족용)
// 2 RPS 고정: 판정 창 최소 표본(30건/3~5분) 대비 20~40배 여유

import http from 'k6/http';

export const options = {
    scenarios: {
        steady: {
            // 도착률 고정(open model) — latency 주입으로 응답이 느려져도 요청률이 유지되어야
            // 최소 표본 조건이 깨지지 않는다 (VU 루프 방식은 지연 시 RPS 가 같이 떨어짐)
            executor: 'constant-arrival-rate',
            rate: 2,
            timeUnit: '1s',
            duration: '168h',
            preAllocatedVUs: 10,
            maxVUs: 50,
        },
    },
};

const BASE = __ENV.TARGET_BASE_URL || 'http://target-app:8080';

export default function () {
    const roll = Math.random();
    if (roll < 0.7) {
        http.get(`${BASE}/products`);
    } else {
        http.get(`${BASE}/products/${Math.ceil(Math.random() * 5)}`);
    }
}
