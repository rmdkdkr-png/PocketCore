/* 프레임 생성 호스트 시험 — gcc -O2 -I app/src/main/cpp tools/fg_test.c app/src/main/cpp/framegen.c
 * 장면: 도트 무늬 배경이 sx px/프레임 스크롤, 그 위로 스프라이트 둘이 다른 속도로 지나간다.
 * 정답 중간 그림(반만큼 옮긴 장면)과 비교해 픽셀 일치율을 낸다. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "framegen.h"
#define W 160
#define H 152
static uint32_t pal[8] = {0x101820,0x2a4060,0x5080a0,0xa0c0d0,0x603020,0xc06040,0xf0d080,0xffffff};
static void put(uint8_t*f,int x,int y,uint32_t c){ if(x<0||y<0||x>=W||y>=H)return; uint8_t*p=f+(y*W+x)*4; p[0]=c>>16;p[1]=c>>8;p[2]=c;p[3]=255; }
static uint32_t bgcol(int x,int y){ unsigned hsh=((unsigned)(x>>2)*73856093u)^((unsigned)(y>>2)*19349663u); return pal[(hsh>>5)%4]; }
/* 위치는 «정수»로 — a=mv/2 를 trunc 하는 생성기와 같은 규칙으로 정답을 그린다 */
static void scene(uint8_t*f,int bgx,int s1x,int s1y,int s2x,int s2y){
  for(int y=0;y<H;y++)for(int x=0;x<W;x++){ uint32_t c=bgcol(x+bgx,y); if(y<16) c=pal[7]; /* 고정 HUD */ put(f,x,y,c);}
  for(int y=0;y<24;y++)for(int x=0;x<16;x++) if(((x-8)*(x-8)+(y-12)*(y-12)/2)<60) put(f,s1x+x,s1y+y,pal[4+((x+y)&1)]);
  for(int y=0;y<20;y++)for(int x=0;x<20;x++) if((x^y)&4) put(f,s2x+x,s2y+y,pal[6]);
}
static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec*1e-9;}
static double match(const uint8_t*a,const uint8_t*b){int ok=0;for(int i=0;i<W*H;i++)if(!memcmp(a+i*4,b+i*4,3))ok++;return 100.0*ok/(W*H);}
int main(void){
  static uint8_t p[W*H*4],c[W*H*4],gt[W*H*4],o[W*H*4];
  /* 경우: {배경 스크롤, 스프1 dx, 스프1 dy, 스프2 dx, 스프2 dy} */
  int cases[][5]={{0,0,0,0,0},{2,0,0,0,0},{0,4,0,-2,0},{2,4,2,-6,0},{1,3,-3,5,1},{3,-6,0,2,2}};
  for(unsigned k=0;k<sizeof cases/sizeof cases[0];k++){
    int*q=cases[k]; int s1x=40,s1y=70,s2x=100,s2y=90;
    /* 배경 스크롤 bgx: 내용이 왼쪽으로 흐름 = 화면상 벡터 -sx */
    scene(p,0,s1x,s1y,s2x,s2y);
    scene(c,q[0],s1x+q[1],s1y+q[2],s2x+q[3],s2y+q[4]);
    scene(gt,(-((-q[0])/2)),s1x+q[1]/2,s1y+q[2]/2,s2x+q[3]/2,s2y+q[4]/2);
    double t0=now(); for(int r=0;r<200;r++) fg_build(p,c,o,W,H,FG_MOTION); double ms=(now()-t0)/200*1000;
    double mm=match(o,gt); fg_build(p,c,o,W,H,FG_BLEND); double mb=match(o,gt);
    printf("case %u bg%+d s1(%+d,%+d) s2(%+d,%+d): motion %.1f%%  blend %.1f%%  prev %.1f%%  cur %.1f%%  [%.2f ms]\n",
      k,q[0],q[1],q[2],q[3],q[4],mm,mb,match(p,gt),match(c,gt),ms);
  }
  return 0;
}
