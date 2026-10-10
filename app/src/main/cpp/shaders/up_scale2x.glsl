/* Scale2x(EPX) — 칸 하나를 넷으로 나눠, 이웃 둘이 같은 색으로 모서리를 이루면 그 색으로 계단을 깎는다.
   (Eric Johnston 의 EPX / Scale2x 규칙을 그대로 옮겨 적음) */
bool eqc(vec3 a, vec3 b) { vec3 d = abs(a - b); return d.r + d.g + d.b < 0.01; }
vec3 up(vec2 p) {
    vec2 c = floor(p); vec2 f = p - c;
    vec3 E = px(c), B = px(c + vec2(0.0, -1.0)), D = px(c + vec2(-1.0, 0.0));
    vec3 F = px(c + vec2(1.0, 0.0)), H = px(c + vec2(0.0, 1.0));
    bool l = f.x < 0.5, t = f.y < 0.5;
    if (t && l)  return (eqc(D, B) && !eqc(B, F) && !eqc(D, H)) ? D : E;
    if (t)       return (eqc(B, F) && !eqc(B, D) && !eqc(F, H)) ? F : E;
    if (l)       return (eqc(D, H) && !eqc(D, B) && !eqc(H, F)) ? D : E;
    return (eqc(H, F) && !eqc(D, H) && !eqc(B, F)) ? F : E;
}
