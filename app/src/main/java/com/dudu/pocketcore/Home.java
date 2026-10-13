package com.dudu.pocketcore;

/** 런처 화면의 «진짜» 액티비티(내부 121~). 하는 일은 전부 MainActivity 에 그대로 있다.
 *
 *  앱 아이콘을 고르게(AppIcon) 하려고 아이콘마다 매니페스트 별칭(activity-alias)을 두는데, 별칭은 실제 클래스를 가리켜야 한다.
 *  120 까지 런처 이름이던 «.MainActivity» 는 이제 그 별칭 하나(기본 아이콘)다 — 이름이 같아서 홈 화면에 붙여 둔 아이콘이 업데이트 뒤에도 그대로 산다.
 *  ★ 앱 안에서 런처를 열 때는 늘 Home.class 로(MainActivity.class 는 아이콘을 바꾸면 꺼지는 별칭이라 못 연다). */
public class Home extends MainActivity { }
