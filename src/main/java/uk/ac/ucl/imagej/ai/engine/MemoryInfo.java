package uk.ac.ucl.imagej.ai.engine;

/**
 * JVM memory state snapshot.
 */
public class MemoryInfo {

    private final long usedMB;
    private final long maxMB;
    private final long freeMB;
    private final int openImageCount;

    public MemoryInfo(long usedMB, long maxMB, long freeMB, int openImageCount) {
        this.usedMB = usedMB;
        this.maxMB = maxMB;
        this.freeMB = freeMB;
        this.openImageCount = openImageCount;
    }

    public long getUsedMB() {
        return usedMB;
    }

    public long getMaxMB() {
        return maxMB;
    }

    public long getFreeMB() {
        return freeMB;
    }

    public int getOpenImageCount() {
        return openImageCount;
    }

    /**
     * Returns memory usage as a percentage (0-100).
     */
    public int getUsagePercent() {
        if (maxMB == 0) {
            return 0;
        }
        return (int) ((usedMB * 100) / maxMB);
    }

    @Override
    public String toString() {
        return "Memory: " + usedMB + "/" + maxMB + " MB (" + getUsagePercent() + "% used), "
                + freeMB + " MB free, " + openImageCount + " images open";
    }
}
