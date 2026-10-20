"""176 음색 다듬기 울림 응답 → app/src/main/cpp/sndfx_ir.h (44.1kHz).
들어 보기 ②(10-20, 유저가 고른 것)와 같은 응답: numpy default_rng(3) 의 standard_normal × exp(−6.9 t / 1.3) → 6kHz 2차 버터워스 저역(sosfilt)
→ 에너지 1(1.3초 전체로) → 앞 1.0초만, int16 으로(배율 SCALE). 좌우는 같은 생성기에서 차례로(왼쪽 먼저).
음악은 음이 몇 개 안 되는 사각파라 같은 대역 에너지라도 응답의 잡음이 다르면 음마다 울림 크기가 달라진다(실측 대역 ±1dB·크기 0.6dB) — 그래서 응답을 그대로 넣는다.
   python3 tools/sndfx_ir_gen.py > app/src/main/cpp/sndfx_ir.h"""
import numpy as np
from scipy.signal import butter, sosfilt
SR = 44100; RT = 1.3; L = int(RT * SR); LT = int(1.0 * SR)
rng = np.random.default_rng(3); t = np.arange(L) / SR; env = np.exp(-6.9 * t / RT)
irs = []
for c in range(2):
    g = rng.standard_normal(L) * env
    g = sosfilt(butter(2, 6000, 'low', fs=SR, output='sos'), g)
    irs.append((g / np.sqrt(np.sum(g ** 2)))[:LT])
pk = max(np.abs(i).max() for i in irs); scale = pk / 32767.0
q = [np.clip(np.rint(i / scale), -32767, 32767).astype(np.int16) for i in irs]
err = max(np.abs(qq * scale - i).max() for qq, i in zip(q, irs))
print('/* 자동 생성 — tools/sndfx_ir_gen.py (들어 보기 ② 와 같은 응답, 44.1kHz, 앞 지연 뺀 1.0초). 값 × SNDFX_IR_SCALE = 응답. 양자화 최대 오차 %.2e */' % err)
print('#define SNDFX_IR_RATE  %d' % SR)
print('#define SNDFX_IR_LEN   %d' % LT)
print('#define SNDFX_IR_SCALE %.9ef' % scale)
print('static const int16_t SNDFX_IR[2][SNDFX_IR_LEN] = {')
for c in range(2):
    print('{')
    v = q[c].tolist()
    for i in range(0, len(v), 24): print(','.join(str(x) for x in v[i:i + 24]) + ',')
    print('},')
print('};')
