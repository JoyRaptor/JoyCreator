package com.fadcam;

public final class FLog {
    private FLog() { }
    public static void w(String tag, String message) { System.err.println(tag + ": " + message); }
}
