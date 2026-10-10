/* Legacito 화면 셰이더 — 공통 머리. 한 패스: (업스케일러 → 섞기) → 필터들(세기별) → 출력.
   좌표 p = vUV * uSize 는 «게임 픽셀 단위»(픽셀 가운데 = .5). 텍스처는 NEAREST 라 px() 로 칸을 직접 집는다. */
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
vec3 px(vec2 t) { return texture2D(uTex, (clamp(t, vec2(0.0), uSize - 1.0) + 0.5) / uSize).rgb; }
vec3 bil(vec2 p) {
    vec2 q = p - 0.5; vec2 i = floor(q); vec2 f = q - i;
    return mix(mix(px(i), px(i + vec2(1.0, 0.0)), f.x),
               mix(px(i + vec2(0.0, 1.0)), px(i + vec2(1.0, 1.0)), f.x), f.y);
}
