"""176·178 음색 다듬기 기준 계산(numpy·scipy, 통째 합성곱) — tools/sndfx_test.c 결과와 견준다.
  python3 tools/sndfx_ref.py in.raw c_out.raw ir_prefix [gain [bass_db treble_db width mix]]
  →  최대·평균 차이(샘플 값), 크기(원래 RMS / 다듬은 RMS)
들어 보기 ②(10-20)와 같은 계산: 셸프 2개(1차 버터워스 — scipy butter 와 같은지 여기서 확인) → 넓힘 → 마른 + 울림.
178: 값(저음 dB · 고음 dB · 넓힘 배수 · 울림 양) — 마른 1−0.4m · 울림 0.64m, 넓힘 왼쪽 +0.35w · 오른쪽 −0.21w. 기본 = ②(3.5 −4 1 0.5).
울림 응답은 C 가 만든 것(ir_prefix0.f32·1.f32 — 앞 지연 포함)을 그대로 쓴다(길이 rt 는 응답에 들어 있다)."""
import sys, numpy as np
from scipy.signal import butter, lfilter, fftconvolve
SR = 44100
inp, cout, irp = sys.argv[1], sys.argv[2], sys.argv[3]
gain = float(sys.argv[4]) if len(sys.argv) > 4 else 1.0
bass, treb, wd, mix = (float(v) for v in sys.argv[5:9]) if len(sys.argv) > 8 else (3.5, -4.0, 1.0, 0.5)
x = np.fromfile(inp, dtype=np.int16).reshape(-1, 2).astype(np.float64)
c = np.fromfile(cout, dtype=np.int16).reshape(-1, 2).astype(np.float64)
irs = [np.fromfile('%s%d.f32' % (irp, ch), dtype=np.float32).astype(np.float64) for ch in (0, 1)]
def first(f, kind):
    K = np.tan(np.pi * f / SR)
    if kind == 'low': b = [K / (1 + K), K / (1 + K)]
    else: b = [1 / (1 + K), -1 / (1 + K)]
    a = [1, (K - 1) / (K + 1)]
    bs, as_ = butter(1, f, kind, fs=SR)
    assert np.allclose(b, bs) and np.allclose(a, as_), (b, bs, a, as_)
    return b, a
def shelf(v, f, gdb, kind):
    b, a = first(f, kind); return v + lfilter(b, a, v, axis=0) * (10 ** (gdb / 20) - 1)
y = shelf(x, 180, bass, 'low'); y = shelf(y, 4500, treb, 'high')
k = int(9 / 1000 * SR); m = (y[:, 0] + y[:, 1]) / 2; d = np.zeros_like(m); d[k:] = m[:-k]
y = np.stack([y[:, 0] + 0.35 * wd * d, y[:, 1] - 0.21 * wd * d], 1)
w = np.stack([fftconvolve(y[:, ch], irs[ch])[:len(y)] for ch in (0, 1)], 1)
o = gain * ((1 - 0.4 * mix) * y + 0.64 * mix * w)
r = np.clip(np.rint(o), -32768, 32767)
diff = np.abs(r - c)
print('샘플 %d · 차이 최대 %.0f · 평균 %.4f · 1 넘는 샘플 %d' % (len(c), diff.max(), diff.mean(), int((diff > 1).sum())))
rms = lambda v: float(np.sqrt(np.mean(v ** 2)))
print('원래 RMS %.1f · 다듬은(배율 1) RMS %.1f · 크기 맞춤 배율 %.4f · C 배율 %.4f(%+.2fdB) · 다듬은 최대 %.0f(원래 %.0f)' % (
    rms(x), rms(o / gain), rms(x) / rms(o / gain), gain, 20 * np.log10(gain / (rms(x) / rms(o / gain))), np.abs(o).max(), np.abs(x).max()))
