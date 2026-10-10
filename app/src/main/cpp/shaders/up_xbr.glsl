/*
   Hyllian's xBR-lv2 Shader

   Copyright (C) 2011-2016 Hyllian - sergiogdb@gmail.com

   Permission is hereby granted, free of charge, to any person obtaining a copy
   of this software and associated documentation files (the "Software"), to deal
   in the Software without restriction, including without limitation the rights
   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
   copies of the Software, and to permit persons to whom the Software is
   furnished to do so, subject to the following conditions:

   The above copyright notice and this permission notice shall be included in
   all copies or substantial portions of the Software.

   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
   THE SOFTWARE.

   Incorporates some of the ideas from SABR shader. Thanks to Joshua Street.

   Legacito: libretro glsl-shaders 의 xbr-lv2.glsl(CORNER_C, SMOOTH_TIPS)을 한 패스·자유 배율로 옮겼다
   (경계 너비 = 화면 1픽셀 — XBR_SCALE 대신 실제 배율). GLSL ES 1.00.
*/
float xbr_cdf(vec3 c1, vec3 c2) { vec3 d = abs(c1 - c2); return d.r + d.g + d.b; }
vec4 xbr_df(vec4 A, vec4 B) { return abs(A - B); }
vec4 xbr_diff(vec4 A, vec4 B) { return vec4(notEqual(A, B)); }
vec4 xbr_eq(vec4 A, vec4 B) { return step(xbr_df(A, B), vec4(15.0)); }
vec4 xbr_neq(vec4 A, vec4 B) { return vec4(1.0) - xbr_eq(A, B); }
vec4 xbr_wd(vec4 a, vec4 b, vec4 c, vec4 d, vec4 e, vec4 f, vec4 g, vec4 h)
{ return xbr_df(a, b) + xbr_df(a, c) + xbr_df(d, e) + xbr_df(d, f) + 4.0 * xbr_df(g, h); }

vec3 up(vec2 p) {
    const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0 );
    const vec4 Bo = vec4( 1.0,  1.0, -1.0,-1.0 );
    const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5 );
    const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0 );
    const vec4 Bx = vec4( 0.5,  2.0, -0.5,-2.0 );
    const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0 );
    const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0 );
    const vec4 By = vec4( 2.0,  0.5, -2.0,-0.5 );
    const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5 );
    const vec4 Ci = vec4(0.25, 0.25, 0.25, 0.25);
    const vec3 rgbw = vec3(14.352, 28.176, 5.472);

    vec2 c0 = floor(p);
    vec2 fp = p - c0;
    float xs = max(min(uOut.x / uSize.x, uOut.y / uSize.y), 1.0);
    vec4 delta   = vec4(1.0 / xs);
    vec4 delta_l = vec4(0.5 / xs, 1.0 / xs, 0.5 / xs, 1.0 / xs);
    vec4 delta_u = delta_l.yxwz;

    vec3 A1 = px(c0 + vec2(-1.0, -2.0)), B1 = px(c0 + vec2(0.0, -2.0)), C1 = px(c0 + vec2(1.0, -2.0));
    vec3 A  = px(c0 + vec2(-1.0, -1.0)), B  = px(c0 + vec2(0.0, -1.0)), C  = px(c0 + vec2(1.0, -1.0));
    vec3 D  = px(c0 + vec2(-1.0,  0.0)), E  = px(c0),                    F  = px(c0 + vec2(1.0,  0.0));
    vec3 G  = px(c0 + vec2(-1.0,  1.0)), H  = px(c0 + vec2(0.0,  1.0)), I  = px(c0 + vec2(1.0,  1.0));
    vec3 G5 = px(c0 + vec2(-1.0,  2.0)), H5 = px(c0 + vec2(0.0,  2.0)), I5 = px(c0 + vec2(1.0,  2.0));
    vec3 A0 = px(c0 + vec2(-2.0, -1.0)), D0 = px(c0 + vec2(-2.0, 0.0)), G0 = px(c0 + vec2(-2.0, 1.0));
    vec3 C4 = px(c0 + vec2( 2.0, -1.0)), F4 = px(c0 + vec2( 2.0, 0.0)), I4 = px(c0 + vec2( 2.0, 1.0));

    vec4 b = vec4(dot(B, rgbw), dot(D, rgbw), dot(H, rgbw), dot(F, rgbw));
    vec4 c = vec4(dot(C, rgbw), dot(A, rgbw), dot(G, rgbw), dot(I, rgbw));
    vec4 d = b.yzwx;
    vec4 e = vec4(dot(E, rgbw));
    vec4 f = b.wxyz;
    vec4 g = c.zwxy;
    vec4 h = b.zwxy;
    vec4 i = c.wxyz;
    vec4 i4 = vec4(dot(I4, rgbw), dot(C1, rgbw), dot(A0, rgbw), dot(G5, rgbw));
    vec4 i5 = vec4(dot(I5, rgbw), dot(C4, rgbw), dot(A1, rgbw), dot(G0, rgbw));
    vec4 h5 = vec4(dot(H5, rgbw), dot(F4, rgbw), dot(B1, rgbw), dot(D0, rgbw));
    vec4 f4 = h5.yzwx;

    vec4 fx   = Ao * fp.y + Bo * fp.x;
    vec4 fx_l = Ax * fp.y + Bx * fp.x;
    vec4 fx_u = Ay * fp.y + By * fp.x;

    vec4 irlv0 = xbr_diff(e, f) * xbr_diff(e, h);
    vec4 irlv1 = irlv0 * (xbr_neq(f, b) * xbr_neq(f, c) + xbr_neq(h, d) * xbr_neq(h, g)
               + xbr_eq(e, i) * (xbr_neq(f, f4) * xbr_neq(f, i4) + xbr_neq(h, h5) * xbr_neq(h, i5))
               + xbr_eq(e, g) + xbr_eq(e, c));
    vec4 irlv2l = xbr_diff(e, g) * xbr_diff(d, g);
    vec4 irlv2u = xbr_diff(e, c) * xbr_diff(b, c);

    vec4 fx45i = clamp((fx   + delta   - Co - Ci) / (2.0 * delta  ), 0.0, 1.0);
    vec4 fx45  = clamp((fx   + delta   - Co     ) / (2.0 * delta  ), 0.0, 1.0);
    vec4 fx30  = clamp((fx_l + delta_l - Cx     ) / (2.0 * delta_l), 0.0, 1.0);
    vec4 fx60  = clamp((fx_u + delta_u - Cy     ) / (2.0 * delta_u), 0.0, 1.0);

    vec4 wd1 = xbr_wd(e, c,  g, i, h5, f4, h, f);
    vec4 wd2 = xbr_wd(h, d, i5, f, i4,  b, e, i);

    vec4 edri  = step(wd1, wd2) * irlv0;
    vec4 edr   = step(wd1 + vec4(0.1), wd2) * step(vec4(0.5), irlv1);
    vec4 edr_l = step(2.0 * xbr_df(f, g), xbr_df(h, c)) * irlv2l * edr;
    vec4 edr_u = step(2.0 * xbr_df(h, c), xbr_df(f, g)) * irlv2u * edr;

    fx45  = edr   * fx45;
    fx30  = edr_l * fx30;
    fx60  = edr_u * fx60;
    fx45i = edri  * fx45i;

    vec4 pxs = step(xbr_df(e, f), xbr_df(e, h));
    vec4 maximos = max(max(fx30, fx60), max(fx45, fx45i));

    vec3 res1 = E;
    res1 = mix(res1, mix(H, F, pxs.x), maximos.x);
    res1 = mix(res1, mix(B, D, pxs.z), maximos.z);
    vec3 res2 = E;
    res2 = mix(res2, mix(F, B, pxs.y), maximos.y);
    res2 = mix(res2, mix(D, H, pxs.w), maximos.w);
    return mix(res1, res2, step(xbr_cdf(E, res1), xbr_cdf(E, res2)));
}
