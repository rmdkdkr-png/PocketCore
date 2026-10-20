"""178 음색 다듬기 크기 맞춤 모델 — 표 만들기와 실측 대조.
  python3 tools/sndfx_model.py spec a.raw [b.raw ...] > app/src/main/cpp/sndfx_spec.h
      SS2 소리(s16 스테레오 44.1kHz raw)들의 평균 전력 스펙트럼(각자 합 1로 맞춰 평균) → 1/6 옥타브 60칸(가운데 22.1Hz~20.2kHz), 합 1.
      10-20 표는 데모 대전(90초, 효과음+곡) + 대전곡 칸 12(51초)로 만들었다.
  python3 tools/sndfx_model.py check sfx_binary a.raw [b.raw ...]
      tools/sndfx_test.c 를 값 격자(설정 0·25·50·75·100)로 돌려, 다듬은 소리 크기가 원래와 얼마나 다른지(dB) — 모델이 맞으면 0 근처.
      ② 배율 0.91 은 데모(0.883)와 대전곡(0.932) 사이라 장면마다 ±0.3dB 는 원래 있다.
C 쪽 모델(sndfx.c model_pow): 셸프 전력 응답(표 가중) × 넓힘(1 + (0.35w² + 0.21w²)/2) × (마른² + 울림² × K), K = 울림 응답 칸 스펙트럼 제곱합(표 가중).
배율 = 0.91 × √(모델(②) / 모델(값))."""
import sys, os, subprocess, itertools, tempfile
import numpy as np


def load(p):
    return np.fromfile(p, dtype='<i2').reshape(-1, 2).astype(np.float64)


def spec(paths):
    from scipy.signal import welch
    SR = 44100
    Ps = []
    for p in paths:
        f, P = welch(load(p).mean(1), fs=SR, nperseg=16384)
        Ps.append(P / P.sum())
    P = np.mean(Ps, 0)
    fc = 1000 * 2.0 ** (np.arange(-33, 27) / 6)
    band = np.array([P[(f >= c * 2 ** (-1 / 12)) & (f < c * 2 ** (1 / 12))].sum() for c in fc])
    band /= band.sum()
    print('/* 자동 생성(tools/sndfx_model.py spec) — SS2 소리(데모 대전 + 대전곡 칸 12) 평균 전력, 1/6 옥타브 %d칸(가운데 %.1f~%.0fHz), 합 1. 음색 다듬기 크기 맞춤 모델 */' % (len(fc), fc[0], fc[-1]))
    print('#define SNDFX_SPEC_N %d' % len(fc))
    print('static const float SNDFX_SPEC_F0 = %.6ff;   /* 첫 칸 가운데(Hz) — 칸마다 × 2^(1/6) */' % fc[0])
    print('static const float SNDFX_SPEC[SNDFX_SPEC_N] = {')
    for i in range(0, len(band), 6):
        print('   ' + ', '.join('%.4ef' % v for v in band[i:i + 6]) + ',')
    print('};')


def check(binary, paths):
    rms = lambda v: float(np.sqrt(np.mean(v ** 2)))
    xs = [load(p) for p in paths]
    errs = []
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, 'o.raw')
        for v in itertools.product((0, 50, 100), (0, 50, 100), (0, 50, 100), (0, 50, 100), (0, 50, 100)):
            row = []
            for p, x in zip(paths, xs):
                env = dict(os.environ, SNDFX_PCT=','.join(map(str, v)))
                subprocess.run([binary, p, out], env=env, check=True, stderr=subprocess.DEVNULL)
                o = load(out)
                row.append(20 * np.log10(rms(o) / rms(x)))
            errs.append(row)
            print('%3d %3d %3d %3d %3d  ' % v + '  '.join('%+.2fdB' % e for e in row), flush=True)
    e = np.array(errs)
    for i, p in enumerate(paths):
        print('%s: 크기 차이 평균 %+.2fdB · 최소 %+.2f · 최대 %+.2f' % (os.path.basename(os.path.dirname(p)) or p, e[:, i].mean(), e[:, i].min(), e[:, i].max()))
    print('장면 평균: 최소 %+.2f · 최대 %+.2f dB' % (e.mean(1).min(), e.mean(1).max()))


if __name__ == '__main__':
    if len(sys.argv) >= 3 and sys.argv[1] == 'spec':
        spec(sys.argv[2:])
    elif len(sys.argv) >= 4 and sys.argv[1] == 'check':
        check(sys.argv[2], sys.argv[3:])
    else:
        print(__doc__)
