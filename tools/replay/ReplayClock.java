package replay;

public final class ReplayClock {
    private static volatile long nowMs;
    private ReplayClock() { }
    public static long nowMs() { return nowMs; }
    public static void setNowMs(long value) { nowMs=value; }
}
