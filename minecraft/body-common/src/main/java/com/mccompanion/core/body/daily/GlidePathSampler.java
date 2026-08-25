package com.mccompanion.core.body.daily;

import java.util.ArrayList;
import java.util.List;

/** Version-neutral bounded sampling for the straight-line portion of a basic elytra glide. */
public final class GlidePathSampler {
    private GlidePathSampler() { }

    public static List<DailyActionRequest.Position> between(
            DailyActionRequest.Position start,
            DailyActionRequest.Position target,
            int maxSamples) {
        if (start == null || target == null || maxSamples < 1
                || !start.dimension().equals(target.dimension())) return List.of();
        int dx = target.x() - start.x();
        int dy = target.y() - start.y();
        int dz = target.z() - start.z();
        int steps = Math.min(maxSamples, Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))));
        if (steps <= 1) return List.of();
        ArrayList<DailyActionRequest.Position> result = new ArrayList<>();
        DailyActionRequest.Position previous = null;
        for (int step = 1; step < steps; step++) {
            double progress = (double) step / steps;
            DailyActionRequest.Position sample = new DailyActionRequest.Position(start.dimension(),
                    (int) Math.round(start.x() + dx * progress),
                    (int) Math.round(start.y() + dy * progress),
                    (int) Math.round(start.z() + dz * progress));
            if (!sample.equals(previous)) result.add(sample);
            previous = sample;
        }
        return List.copyOf(result);
    }
}
