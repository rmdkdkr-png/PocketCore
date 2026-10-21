/* 샤프 바이리니어 — 정수배까지는 도트 그대로, 남는 소수 배율만 칸 경계에서 부드럽게(칸 크기 들쭉날쭉 없앰) */
vec3 up(vec2 p) {
    vec2 sc = max(floor(uOut / uSize), 1.0);
    vec2 fl = floor(p);
    vec2 cd = (p - fl) - 0.5;
    vec2 rr = 0.5 - 0.5 / sc;
    vec2 ff = (cd - clamp(cd, -rr, rr)) * sc + 0.5;
    return bil(fl + ff);
}
