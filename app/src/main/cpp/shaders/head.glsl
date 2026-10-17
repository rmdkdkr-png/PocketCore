/* Legacito 화면 셰이더 — 공통 머리. 한 패스: (업스케일러 → 섞기) → 필터들(세기별) → 출력.
   좌표 p = vUV * uSize 는 «게임 픽셀 단위»(픽셀 가운데 = .5). 텍스처는 NEAREST 라 px() 로 칸을 직접 집는다.
   4배 그림(uSub 4)이면 색은 텍셀 그대로, 업스케일러는 쪽지(uPh)로 화소마다 제 모눈에서 — 격자·스캔라인 줄은 늘 화면의 게임 칸(p) */
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUV;
uniform sampler2D uTex;
uniform vec2 uSize;     /* 게임 그림 크기(픽셀) */
uniform vec2 uOut;      /* 화면에 그려지는 크기(픽셀) */
uniform float uUpMix;   /* 업스케일러 섞기 0..1 (0 = 도트 그대로) */
uniform float uGrid;    /* LCD 격자 세기 */
uniform float uScan;    /* 스캔라인 세기 */
uniform float uColor;   /* LCD 색감 세기 */
uniform float uSoft;    /* 부드럽게(번짐) 세기 */
uniform float uSub;     /* 텍스처가 게임 칸의 몇 배인가 — 1, 또는 사무쇼2 프레임 패치의 4배 그림(640×608)이면 4 */
uniform float uPhase;   /* 1 = 쪽지(uPh) 있음 — 4배 그림 화소마다 원래 화소의 4×4 칸이 몇 칸 비켜 시작하나 */
uniform sampler2D uPh;  /* 쪽지: x 0..3 | y 0..3 << 2 (한 바이트) */
/* 이 화소가 속한 원래 화소 모눈의 어긋남(4배 화소, 0..3) — main 이 쪽지에서 정한다. px(t) 의 t 는 그 모눈 기준 게임 칸.
   프레임 패치는 캐릭터·배경을 ¼ px 옮겨 4배 그림에 그린다 — 업스케일러가 화소마다 제 모눈으로 원래 화소를 읽어야 ¼ 자리가 산다 */
vec2 gPh = vec2(0.0);
vec3 px(vec2 t) {
    vec2 g = clamp(t, vec2(0.0), uSize - 1.0);
    return texture2D(uTex, (g * uSub + gPh + 0.5 * uSub) / (uSize * uSub)).rgb;   /* 그 원래 화소의 4×4 칸 가운데 */
}
vec3 bil(vec2 p) {
    vec2 q = p - 0.5; vec2 i = floor(q); vec2 f = q - i;
    return mix(mix(px(i), px(i + vec2(1.0, 0.0)), f.x),
               mix(px(i + vec2(0.0, 1.0)), px(i + vec2(1.0, 1.0)), f.x), f.y);
}
