/*
OmniScale
by Lior Halphon (ported to RetroArch glsl by hunterk)

MIT License

Copyright (c) 2015-2016 Lior Halphon

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

Legacito: GLSL ES 1.00 엔 비트 연산이 없어 P(m, r) 패턴 검사를 생성기(tools/gen_shaders.py)가 불 대수로 펼쳤다.
이웃은 칸 단위로 집는다(px). 출력은 vec3.
*/
vec3 om_hq(vec3 rgb) {
    return vec3( 0.250 * rgb.r + 0.250 * rgb.g + 0.250 * rgb.b,
                 0.250 * rgb.r - 0.000 * rgb.g - 0.250 * rgb.b,
                -0.125 * rgb.r + 0.250 * rgb.g - 0.125 * rgb.b);
}
bool om_diff(vec3 a, vec3 b) {
    vec3 d = abs(om_hq(a) - om_hq(b));
    return d.x > 0.125 || d.y > 0.027 || d.z > 0.031;
}
vec3 up(vec2 pos) {
    vec2 c = floor(pos);
    vec2 o = vec2(1.0);
    vec2 p = pos - c;
    if (p.x > 0.5) { o.x = -o.x; p.x = 1.0 - p.x; }
    if (p.y > 0.5) { o.y = -o.y; p.y = 1.0 - p.y; }
    vec3 w0 = px(c + vec2(-o.x, -o.y));
    vec3 w1 = px(c + vec2( 0.0, -o.y));
    vec3 w2 = px(c + vec2( o.x, -o.y));
    vec3 w3 = px(c + vec2(-o.x,  0.0));
    vec3 w4 = px(c);
    vec3 w5 = px(c + vec2( o.x,  0.0));
    vec3 w6 = px(c + vec2(-o.x,  o.y));
    vec3 w7 = px(c + vec2( 0.0,  o.y));
    vec3 w8 = px(c + vec2( o.x,  o.y));
    bool d0 = om_diff(w0, w4), d1 = om_diff(w1, w4), d2 = om_diff(w2, w4), d3 = om_diff(w3, w4);
    bool d4 = om_diff(w5, w4), d5 = om_diff(w6, w4), d6 = om_diff(w7, w4), d7 = om_diff(w8, w4);
    float pixel_size = length(uSize / uOut);

    if ((P(0xbf,0x37) || P(0xdb,0x13)) && om_diff(w1, w5))
        return mix(w4, w3, 0.5 - p.x);
    if ((P(0xdb,0x49) || P(0xef,0x6d)) && om_diff(w7, w3))
        return mix(w4, w1, 0.5 - p.y);
    if ((P(0x0b,0x0b) || P(0xfe,0x4a) || P(0xfe,0x1a)) && om_diff(w3, w1))
        return w4;
    if ((P(0x6f,0x2a) || P(0x5b,0x0a) || P(0xbf,0x3a) || P(0xdf,0x5a) ||
         P(0x9f,0x8a) || P(0xcf,0x8a) || P(0xef,0x4e) || P(0x3f,0x0e) ||
         P(0xfb,0x5a) || P(0xbb,0x8a) || P(0x7f,0x5a) || P(0xaf,0x8a) ||
         P(0xeb,0x8a)) && om_diff(w3, w1))
        return mix(w4, mix(w4, w0, 0.5 - p.x), 0.5 - p.y);
    if (P(0x0b,0x08))
        return mix(mix(w0 * 0.375 + w1 * 0.25 + w4 * 0.375, w4 * 0.5 + w1 * 0.5, p.x * 2.0), w4, p.y * 2.0);
    if (P(0x0b,0x02))
        return mix(mix(w0 * 0.375 + w3 * 0.25 + w4 * 0.375, w4 * 0.5 + w3 * 0.5, p.y * 2.0), w4, p.x * 2.0);
    if (P(0x2f,0x2f)) {
        float dist = length(p - vec2(0.5));
        if (dist < 0.5 - pixel_size / 2.0) return w4;
        vec3 r;
        if (om_diff(w0, w1) || om_diff(w0, w3)) r = mix(w1, w3, p.y - p.x + 0.5);
        else r = mix(mix(w1 * 0.375 + w0 * 0.25 + w3 * 0.375, w3, p.y * 2.0), w1, p.x * 2.0);
        if (dist > 0.5 + pixel_size / 2.0) return r;
        return mix(w4, r, (dist - 0.5 + pixel_size / 2.0) / pixel_size);
    }
    if (P(0xbf,0x37) || P(0xdb,0x13)) {
        float dist = p.x - 2.0 * p.y;
        float ps5 = pixel_size * sqrt(5.0);
        if (dist > ps5 / 2.0) return w1;
        vec3 r = mix(w3, w4, p.x + 0.5);
        if (dist < -ps5 / 2.0) return r;
        return mix(r, w1, (dist + ps5 / 2.0) / ps5);
    }
    if (P(0xdb,0x49) || P(0xef,0x6d)) {
        float dist = p.y - 2.0 * p.x;
        float ps5 = pixel_size * sqrt(5.0);
        if (p.y - 2.0 * p.x > ps5 / 2.0) return w3;
        vec3 r = mix(w1, w4, p.x + 0.5);
        if (dist < -ps5 / 2.0) return r;
        return mix(r, w3, (dist + ps5 / 2.0) / ps5);
    }
    if (P(0xbf,0x8f) || P(0x7e,0x0e)) {
        float dist = p.x + 2.0 * p.y;
        float ps5 = pixel_size * sqrt(5.0);
        if (dist > 1.0 + ps5 / 2.0) return w4;
        vec3 r;
        if (om_diff(w0, w1) || om_diff(w0, w3)) r = mix(w1, w3, p.y - p.x + 0.5);
        else r = mix(mix(w1 * 0.375 + w0 * 0.25 + w3 * 0.375, w3, p.y * 2.0), w1, p.x * 2.0);
        if (dist < 1.0 - ps5 / 2.0) return r;
        return mix(r, w4, (dist + ps5 / 2.0 - 1.0) / ps5);
    }
    if (P(0x7e,0x2a) || P(0xef,0xab)) {
        float dist = p.y + 2.0 * p.x;
        float ps5 = pixel_size * sqrt(5.0);
        if (p.y + 2.0 * p.x > 1.0 + ps5 / 2.0) return w4;
        vec3 r;
        if (om_diff(w0, w1) || om_diff(w0, w3)) r = mix(w1, w3, p.y - p.x + 0.5);
        else r = mix(mix(w1 * 0.375 + w0 * 0.25 + w3 * 0.375, w3, p.y * 2.0), w1, p.x * 2.0);
        if (dist < 1.0 - ps5 / 2.0) return r;
        return mix(r, w4, (dist + ps5 / 2.0 - 1.0) / ps5);
    }
    if (P(0x1b,0x03) || P(0x4f,0x43) || P(0x8b,0x83) || P(0x6b,0x43))
        return mix(w4, w3, 0.5 - p.x);
    if (P(0x4b,0x09) || P(0x8b,0x89) || P(0x1f,0x19) || P(0x3b,0x19))
        return mix(w4, w1, 0.5 - p.y);
    if (P(0xfb,0x6a) || P(0x6f,0x6e) || P(0x3f,0x3e) || P(0xfb,0xfa) ||
        P(0xdf,0xde) || P(0xdf,0x1e))
        return mix(w4, w0, (1.0 - p.x - p.y) / 2.0);
    if (P(0x4f,0x4b) || P(0x9f,0x1b) || P(0x2f,0x0b) ||
        P(0xbe,0x0a) || P(0xee,0x0a) || P(0x7e,0x0a) || P(0xeb,0x4b) ||
        P(0x3b,0x1b)) {
        float dist = p.x + p.y;
        if (dist > 0.5 + pixel_size / 2.0) return w4;
        vec3 r;
        if (om_diff(w0, w1) || om_diff(w0, w3)) r = mix(w1, w3, p.y - p.x + 0.5);
        else r = mix(mix(w1 * 0.375 + w0 * 0.25 + w3 * 0.375, w3, p.y * 2.0), w1, p.x * 2.0);
        if (dist < 0.5 - pixel_size / 2.0) return r;
        return mix(r, w4, (dist + pixel_size / 2.0 - 0.5) / pixel_size);
    }
    if (P(0x0b,0x01))
        return mix(mix(w4, w3, 0.5 - p.x), mix(w1, (w1 + w3) / 2.0, 0.5 - p.x), 0.5 - p.y);
    if (P(0x0b,0x00))
        return mix(mix(w4, w3, 0.5 - p.x), mix(w1, w0, 0.5 - p.x), 0.5 - p.y);

    float dist = p.x + p.y;
    if (dist > 0.5 + pixel_size / 2.0) return w4;

    /* 대각선을 풀려면 이웃을 더 본다 — 다른 칸 수를 세어 기울기를 정한다(원본의 비트 세기) */
    vec3 x0 = px(c + vec2(-o.x * 2.0, -o.y * 2.0));
    vec3 x1 = px(c + vec2(-o.x,       -o.y * 2.0));
    vec3 x2 = px(c + vec2( 0.0,       -o.y * 2.0));
    vec3 x3 = px(c + vec2( o.x,       -o.y * 2.0));
    vec3 x4 = px(c + vec2(-o.x * 2.0, -o.y      ));
    vec3 x5 = px(c + vec2(-o.x * 2.0,  0.0      ));
    vec3 x6 = px(c + vec2(-o.x * 2.0,  o.y      ));
    float bias = -7.0
        + (d0 ? 1.0 : 0.0) + (d1 ? 1.0 : 0.0) + (d2 ? 1.0 : 0.0) + (d3 ? 1.0 : 0.0)
        + (d4 ? 1.0 : 0.0) + (d5 ? 1.0 : 0.0) + (d6 ? 1.0 : 0.0) + (d7 ? 1.0 : 0.0)
        + (om_diff(x0, w4) ? 1.0 : 0.0) + (om_diff(x1, w4) ? 1.0 : 0.0) + (om_diff(x2, w4) ? 1.0 : 0.0)
        + (om_diff(x3, w4) ? 1.0 : 0.0) + (om_diff(x4, w4) ? 1.0 : 0.0) + (om_diff(x5, w4) ? 1.0 : 0.0)
        + (om_diff(x6, w4) ? 1.0 : 0.0);
    if (bias <= 0.0) {
        vec3 r = mix(w1, w3, p.y - p.x + 0.5);
        if (dist < 0.5 - pixel_size / 2.0) return r;
        return mix(r, w4, (dist + pixel_size / 2.0 - 0.5) / pixel_size);
    }
    return w4;
}
