void main() {
    vec2 p = vUV * uSize;
    vec3 nn = px(floor(p));
    vec3 c = uUpMix > 0.0 ? mix(nn, up(p), uUpMix) : nn;
    if (uSoft > 0.0) c = mix(c, bil(p), uSoft);
    if (uColor > 0.0) {                                   /* LCD 색감: 채도·대비를 조금 눌러 실기 화면처럼 */
        vec3 l = vec3(dot(c, vec3(0.299, 0.587, 0.114)));
        vec3 lcd = pow(mix(l, c, 0.80), vec3(1.12)) * 0.92 + vec3(0.045, 0.05, 0.035);
        c = mix(c, lcd, uColor);
    }
    vec2 sc = uOut / uSize;                               /* 게임 픽셀 하나 = 화면 몇 픽셀 */
    vec2 f = fract(p);
    if (uGrid > 0.0) {                                    /* LCD 격자: 칸 사이 가는 어두운 줄 */
        vec2 e = min(f, 1.0 - f) * sc;
        float w = max(1.0, min(sc.x, sc.y) * 0.10);
        float g = 1.0 - smoothstep(w * 0.5 - 0.5, w * 0.5 + 0.5, min(e.x, e.y));
        c *= 1.0 - uGrid * 0.6 * g;
    }
    if (uScan > 0.0) {                                    /* 스캔라인: 줄마다 아래위를 어둡게 */
        float d = abs(f.y - 0.5) * 2.0;
        c *= 1.0 - uScan * 0.55 * smoothstep(0.45, 1.0, d);
    }
    gl_FragColor = vec4(c, 1.0);
}
