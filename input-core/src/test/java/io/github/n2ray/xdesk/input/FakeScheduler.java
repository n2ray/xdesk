package io.github.n2ray.xdesk.input;

import java.util.ArrayList;
import java.util.List;

/** A clock the test moves by hand; posted runnables run when their time comes. */
final class FakeScheduler implements Scheduler {
    private static final class Job {
        final Runnable r;
        final long at;
        Job(Runnable r, long at) { this.r = r; this.at = at; }
    }

    private final List<Job> jobs = new ArrayList<>();
    private long now;

    @Override public void post(Runnable r, long delayMs) {
        jobs.add(new Job(r, now + delayMs));
    }

    @Override public void cancel(Runnable r) {
        jobs.removeIf(j -> j.r == r);
    }

    @Override public long now() {
        return now;
    }

    /** Runs everything due up to this time, in time order, with the clock at each job's time. */
    void advance(long to) {
        while (true) {
            Job next = null;
            for (Job j : jobs) if (j.at <= to && (next == null || j.at < next.at)) next = j;
            if (next == null) break;
            jobs.remove(next);
            if (next.at > now) now = next.at;
            next.r.run();
        }
        if (to > now) now = to;
    }
}
