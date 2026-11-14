package com.ygoengine;

import java.nio.file.Files;
import java.nio.file.Path;

/** Plays a live duel from a saved request, bot moves on a normal Java thread (as in the app). */
public final class WinRepro {
    public static void main(String[] a) throws Exception {
        long h = YgoNative.create(a[0], a[0]);
        String req = Files.readString(Path.of(a[1])).replaceFirst("\"seed\":\\d+", "\"seed\":" + a[2]);
        long d = YgoNative.liveCreate(h, req);
        if (d == 0) { System.out.println("create failed: " + YgoNative.lastError()); return; }
        int level = Integer.parseInt(a[3]);
        int max = Integer.parseInt(a[4]);
        final String[] m = new String[1];
        for (int i = 0; i < max; i++) {
            Thread t = new Thread(() -> m[0] = YgoNative.liveBot(d, level));
            t.start();
            t.join();
            System.out.println("move " + i + ": " + (m[0].length() > 80 ? m[0].substring(0, 80) : m[0]));
            if (m[0].isEmpty()) break;
            YgoNative.liveState(d);
        }
        System.out.println("finished");
    }
}
