package tech.ydb.importer.benchmark;

final class MemoryTracker {

    private final long intervalMs;
    private volatile boolean running;
    private volatile long peakUsedBytes;
    private Thread thread;

    MemoryTracker(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    void start() {
        running = true;
        peakUsedBytes = 0;
        thread = new Thread(() -> {
            while (running) {
                sample();
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "MemoryTracker");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(intervalMs * 2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        sample();
    }

    long getPeakUsedBytes() {
        return peakUsedBytes;
    }

    private void sample() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        if (used > peakUsedBytes) {
            peakUsedBytes = used;
        }
    }
}
