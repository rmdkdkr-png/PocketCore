package com.dudu.pocketcore;

/**
 * Games.java(앱의 게임 정체성 단일표)를 순수 JDK 로 컴파일해 games.identity.json 을 뽑는다.
 * 배포 스크립트(games_data.py)가 이걸 읽는다 — 게임 목록·코어·기능(features)을 파이썬에
 * 다시 적지 않기 위해서다. Games.java 는 android.* 를 안 쓰므로 Android SDK 없이 돈다.
 *
 *   cd pocketcore/pocketcore
 *   javac -d /tmp/gx app/src/main/java/com/dudu/pocketcore/Games.java tools/ExportGames.java
 *   java -cp /tmp/gx com.dudu.pocketcore.ExportGames > tools/games.identity.json
 *
 * 순서는 Games.ALL 그대로(identify 순서 — 구체 tag 먼저). 배포 표시 순서는 catalog 의 displayOrder.
 */
public final class ExportGames {
    public static void main(String[] a) {
        Games.Game[] all = Games.all();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n \"ver\": 1,\n \"source\": \"Games.java (app SSOT) — 손대지 말고 ExportGames 로 재생성\",\n \"games\": [\n");
        for (int i = 0; i < all.length; i++) {
            Games.Game g = all[i];
            sb.append("  {\"id\":").append(q(g.id))
              .append(", \"platform\":").append(q(g.platform))
              .append(", \"tag\":").append(q(g.tag))
              .append(", \"ko\":").append(q(g.ko))
              .append(", \"core\":").append(q(g.core))
              .append(", \"patchable\":").append(g.patchable)
              .append(", \"baseLang\":").append(q(g.baseLang))
              .append(", \"voice\":").append(g.voice == null ? "null" : q(g.voice))
              .append(", \"features\":[");
            for (int j = 0; j < g.features.length; j++) {
                if (j > 0) sb.append(",");
                sb.append(q(g.features[j]));
            }
            sb.append("]}").append(i < all.length - 1 ? "," : "").append("\n");
        }
        sb.append(" ],\n \"displayOrder\": [");
        for (int i = 0; i < Games.DISPLAY_ORDER.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(q(Games.DISPLAY_ORDER[i]));
        }
        sb.append("]\n}\n");
        System.out.print(sb);
    }
    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
