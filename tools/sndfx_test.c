/* 호스트 시험(176 · 178): 음색 다듬기(sndfx.c)를 s16 스테레오 raw 에 — 소리 카드 콜백처럼 들쭉날쭉한 묶음으로 나눠 돌리고, 걸린 시간·응답을 남긴다.
 *   cc -O2 -I app/src/main/cpp tools/sndfx_test.c app/src/main/cpp/sndfx.c -lm -o /tmp/sfx && /tmp/sfx in.raw out.raw [ir_prefix]
 *   178: 환경 변수 SNDFX_PCT="저음,고음,넓힘,울림,길이"(설정 값 0..100, 50 = ②) · SNDFX_RATE(기본 44100)
 *   tools/sndfx_ref.py 가 같은 계산을 numpy(합성곱 통째)로 해서 견준다. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "sndfx.h"

int main(int argc, char **argv)
{
   FILE *fi, *fo; long n; int16_t *a; sndfx_t *fx; size_t done = 0; unsigned s = 7; struct timespec t0, t1; double ns;
   static const int sizes[] = { 192, 240, 96, 1, 1000, 512, 37, 4096, 160 };
   if (argc < 3) { fprintf(stderr, "usage: in.raw out.raw [ir_prefix]\n"); return 1; }
   fi = fopen(argv[1], "rb"); fseek(fi, 0, SEEK_END); n = ftell(fi) / 4; fseek(fi, 0, SEEK_SET);
   a = malloc((size_t)n * 4); if (fread(a, 4, (size_t)n, fi) != (size_t)n) return 1; fclose(fi);
   {
      sndfx_params prm; int v[5] = { 50, 50, 50, 50, 50 }, rate = getenv("SNDFX_RATE") ? atoi(getenv("SNDFX_RATE")) : 44100;
      const char *e = getenv("SNDFX_PCT");
      if (e) sscanf(e, "%d,%d,%d,%d,%d", &v[0], &v[1], &v[2], &v[3], &v[4]);
      sndfx_from_pct(&prm, v[0], v[1], v[2], v[3], v[4]);
      fprintf(stderr, "params bass %.2fdB treble %.2fdB width %.2f mix %.2f rt %.3fs rate %d\n", prm.bass_db, prm.treble_db, prm.width, prm.mix, prm.rt, rate);
      clock_gettime(CLOCK_MONOTONIC, &t0);
      fx = sndfx_new(rate, &prm);
      clock_gettime(CLOCK_MONOTONIC, &t1);
   }
   if (!fx) { fprintf(stderr, "sndfx_new failed\n"); return 1; }
   fprintf(stderr, "init %.1f ms\n", (t1.tv_sec - t0.tv_sec) * 1e3 + (t1.tv_nsec - t0.tv_nsec) / 1e6);
   clock_gettime(CLOCK_MONOTONIC, &t0);
   while (done < (size_t)n) {
      size_t k = (size_t)sizes[(s = s * 1103515245u + 12345u) >> 16 & 7];
      if (k > (size_t)n - done) k = (size_t)n - done;
      sndfx_run(fx, a + done * 2, k); done += k;
   }
   clock_gettime(CLOCK_MONOTONIC, &t1);
   ns = (t1.tv_sec - t0.tv_sec) * 1e9 + (t1.tv_nsec - t0.tv_nsec);
   fprintf(stderr, "run %.1f ms for %.1f s audio = %.2f%% of real time, %.1f ns/frame\n", ns / 1e6, n / 44100.0, ns / 1e9 / (n / 44100.0) * 100, ns / n);
   fo = fopen(argv[2], "wb"); fwrite(a, 4, (size_t)n, fo); fclose(fo);
   {  /* 소리 카드 콜백 한 번(192 프레임 = 4.35ms)마다 걸린 시간 — 칸 끝에 일이 몰리지 않는지 */
      size_t calls = 0, m; double *ts = malloc(sizeof(double) * ((size_t)n / 192 + 1)), mx = 0, sum = 0;
      sndfx_reset(fx);
      for (m = 0; m + 192 <= (size_t)n; m += 192) {
         clock_gettime(CLOCK_MONOTONIC, &t0); sndfx_run(fx, a + m * 2, 192); clock_gettime(CLOCK_MONOTONIC, &t1);
         ts[calls] = (t1.tv_sec - t0.tv_sec) * 1e6 + (t1.tv_nsec - t0.tv_nsec) / 1e3; sum += ts[calls]; if (ts[calls] > mx) mx = ts[calls]; calls++;
      }
      { size_t i, j, cnt = 0; for (i = 0; i < calls; i++) if (ts[i] > 4 * sum / calls) cnt++;
        for (i = 1; i < calls; i++) { double v = ts[i]; for (j = i; j > 0 && ts[j - 1] > v; j--) ts[j] = ts[j - 1]; ts[j] = v; }
        fprintf(stderr, "callback 192: mean %.1f us, p99 %.1f us, max %.1f us (budget 4354 us), >4x mean %zu/%zu\n", sum / calls, ts[calls * 99 / 100], mx, cnt, calls); }
      free(ts);
   }
   if (argc > 3) {
      int c; char p[512];
      for (c = 0; c < 2; c++) { size_t len; const float *ir = sndfx_ir(fx, c, &len); FILE *f; snprintf(p, sizeof p, "%s%d.f32", argv[3], c); f = fopen(p, "wb"); fwrite(ir, 4, len, f); fclose(f); }
      fprintf(stderr, "gain %.6f parts %d\n", sndfx_gain(fx), sndfx_parts(fx));
   }
   sndfx_free(fx); free(a);
   return 0;
}
