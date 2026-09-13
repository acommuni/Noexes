package me.mdbell.util;

public class Rolling {

    private int size;
    private double totalSize = 0d;
    private long totalDuration = 0;
    private int index = 0;
    private double samples[];
    private long durations[];

    public Rolling(int size) {
        this.size = size;
        samples = new double[size];
        durations = new long[size];
        for (int i = 0; i < size; i++) {
            samples[i] = 0d;
            durations[i] = 0;
        }
    }

    public void add(double x, long duration) {
        totalSize -= samples[index];
        samples[index] = x;
        totalSize += x;

        totalDuration -= durations[index];
        durations[index] = duration;
        totalDuration += duration;

        if (++index == size)
            index = 0; // cheaper than modulus
    }

    public double getAverage() {
        double res = 0.0;
        if (totalDuration > 0) {
            Double tmp = totalSize / (totalDuration / 1000);
            if (Double.isFinite(tmp)) {
                res = tmp;
            }
        }

        return res;
    }
}
