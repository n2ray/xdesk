/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: AudioPlayout.java, a port of iOSClient/AudioPlayout.swift).
 * Copyright 2026 Noah Saffer. Licensed under the Apache License, Version 2.0. Modified for XDesk:
 * the package and the class name; records are written out (XDesk builds as Java 9). SALL-SYNC.md
 * says which Sall commit this follows and how to compare later.
 */
package com.termux.x11.mac;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure port of iOSClient/AudioPlayout.swift. Times are monotonic seconds except Mac stamps.
 * This class does not drive AudioTrack: its caller must supply an actual render timeline,
 * execute schedules (including PCM edits), and own the original AudioOutput idle timer.
 */
final class SyncedAudioPlayout {
    static final double RATE = 48000, MARGIN = .002;
    static final int FADE_FRAMES = 240;
    enum Part { BODY, TAIL }
    enum Reset { SESSION, PLAYBACK, JUMP }
    enum JumpCause { STEP, ERROR, AHEAD, ROUTE }

    static final class Decision {
        final double due, ear, picture;

        Decision(double due, double ear, double picture) {
            this.due = due;
            this.ear = ear;
            this.picture = picture;
        }

        double due() { return due; }
        double ear() { return ear; }
        double picture() { return picture; }
        double error() { return due - ear; }
    }

    static final class Schedule {
        final int id;
        final Part part;
        final int offset, count;
        final Double at;
        final double start;
        final int frames, change;
        final boolean fadeIn, fadeOut;
        final double stamp;
        final long seq;
        final int epoch;
        final Decision decision;
        final double goesOut;

        Schedule(int id, Part part, int offset, int count, Double at, double start,
                 int frames, int change, boolean fadeIn, boolean fadeOut, double stamp,
                 long seq, int epoch, Decision decision, double goesOut) {
            this.id = id;
            this.part = part;
            this.offset = offset;
            this.count = count;
            this.at = at;
            this.start = start;
            this.frames = frames;
            this.change = change;
            this.fadeIn = fadeIn;
            this.fadeOut = fadeOut;
            this.stamp = stamp;
            this.seq = seq;
            this.epoch = epoch;
            this.decision = decision;
            this.goesOut = goesOut;
        }

        int id() { return id; }
        Part part() { return part; }
        int offset() { return offset; }
        int count() { return count; }
        Double at() { return at; }
        double start() { return start; }
        int frames() { return frames; }
        int change() { return change; }
        boolean fadeIn() { return fadeIn; }
        boolean fadeOut() { return fadeOut; }
        double stamp() { return stamp; }
        long seq() { return seq; }
        int epoch() { return epoch; }
        Decision decision() { return decision; }
        double goesOut() { return goesOut; }
        double end() { return start + frames; }
    }

    static final class Output {
        final List<Schedule> schedules = new ArrayList<>();
        final List<Integer> discards = new ArrayList<>();
        void add(Output other) { schedules.addAll(other.schedules); discards.addAll(other.discards); }
    }

    static final class Plan {
        boolean decode, resetFirst;
        int dropFront;
        Integer id;
        Output output = new Output();
    }

    static final class Second {
        Integer behindMs;
        int placed, late, duplicates, framesAdded, framesDropped, jumps, joins, jumpDrops;
        double need, cover, delay;
        Double lag, error;
    }

    static final class Jump {
        final double by;
        final JumpCause cause;

        Jump(double by, JumpCause cause) {
            this.by = by;
            this.cause = cause;
        }

        double by() { return by; }
        JumpCause cause() { return cause; }
    }

    private static final class Admitted {
        final int id;
        final int epoch;
        final long seq;
        final double stamp;
        final int frames;
        final boolean fadeIn, segmentStart;

        Admitted(int id, int epoch, long seq, double stamp, int frames,
                 boolean fadeIn, boolean segmentStart) {
            this.id = id;
            this.epoch = epoch;
            this.seq = seq;
            this.stamp = stamp;
            this.frames = frames;
            this.fadeIn = fadeIn;
            this.segmentStart = segmentStart;
        }

        int id() { return id; }
        int epoch() { return epoch; }
        long seq() { return seq; }
        double stamp() { return stamp; }
        int frames() { return frames; }
        boolean fadeIn() { return fadeIn; }
        boolean segmentStart() { return segmentStart; }
    }

    private static final class Planned {
        final Admitted packet;
        final double start;
        final boolean contiguous;
        final int change;
        final boolean fadeIn;
        final Decision decision;

        Planned(Admitted packet, double start, boolean contiguous, int change,
                boolean fadeIn, Decision decision) {
            this.packet = packet;
            this.start = start;
            this.contiguous = contiguous;
            this.change = change;
            this.fadeIn = fadeIn;
            this.decision = decision;
        }

        Admitted packet() { return packet; }
        double start() { return start; }
        boolean contiguous() { return contiguous; }
        int change() { return change; }
        boolean fadeIn() { return fadeIn; }
        Decision decision() { return decision; }

        int tailCount() { return Math.min(FADE_FRAMES, packet.frames); }
        int bodyCount() { return packet.frames - tailCount(); }
        int bodyFrames() { return bodyCount() + change; }
        double tailStart() { return start + bodyFrames(); }
        double end() { return tailStart() + tailCount(); }
        boolean takes(Admitted next) {
            return next.epoch == packet.epoch && next.seq == increment(packet.seq)
                    && !next.segmentStart && !next.fadeIn;
        }
        Schedule body(double goesOut) {
            if (bodyCount() == 0) return null;
            return new Schedule(packet.id, Part.BODY, 0, bodyCount(), contiguous ? null : start,
                    start, bodyFrames(), change, fadeIn, false, packet.stamp, packet.seq,
                    packet.epoch, decision, goesOut);
        }
        Schedule tail(boolean fadeOut, double goesOut) {
            boolean first = bodyCount() == 0;
            return new Schedule(packet.id, Part.TAIL, bodyCount(), tailCount(),
                    first && !contiguous ? start : null, tailStart(), tailCount(), 0,
                    first && fadeIn, fadeOut, packet.stamp, packet.seq, packet.epoch, decision, goesOut);
        }
    }

    private static final class Bucket {
        final long second;
        double min;
        Double max;
        Bucket(long second, double min, Double max) { this.second = second; this.min = min; this.max = max; }
    }

    private static final class Above {
        final double since;
        int count;
        double min, max;
        Above(double arrival, double raw) { since = arrival; min = max = raw; }
    }

    Integer epoch;
    int framesPerPacket = 480, primingFrames = 240;
    private byte[] cookie = new byte[0];
    double outputLatency, ioBuffer = .005, refresh = 1.0 / 60, streamFPS = 60;
    Double rttMedian;
    boolean away, muted, placedThisSession;
    private final List<Bucket> buckets = new ArrayList<>();
    private Double floorKept, sessionStart;
    private Above above;
    private final List<Double> frameArrivals = new ArrayList<>(), frameRaws = new ArrayList<>(), sortedRaws = new ArrayList<>();
    private int frameHead;
    private Double lastPictureRaw;
    private final List<Long> recentKeys = new ArrayList<>();
    private final Set<Long> recentSet = new HashSet<>();
    private int recentNext;
    private Long expectedSeq;
    private int primingLeft;
    private boolean fadeNext = true;
    private Double mappingSample, mappingTime;
    private Planned hand;
    private Double chainEnd;
    private final List<Admitted> waiting = new ArrayList<>();
    private int nextID = 1, correcting;
    private boolean jumpOwed;
    private JumpCause owedCause;
    private Jump madeJump;
    Double lastError;
    private Second second = new Second();
    private final List<Double> behind = new ArrayList<>();

    /** The model's last placement error in frames (due - ear), for the once-a-minute diagnostic. */
    Double lastError() { return lastError; }

    boolean format(int epoch, int framesPerPacket, int primingFrames, byte[] cookie) {
        if (this.epoch != null && this.epoch == epoch && Arrays.equals(this.cookie, cookie)
                && this.framesPerPacket == framesPerPacket && this.primingFrames == primingFrames) return false;
        this.epoch = epoch;
        this.framesPerPacket = Math.max(1, framesPerPacket);
        this.primingFrames = Math.max(0, primingFrames);
        this.cookie = cookie.clone();
        expectedSeq = null;
        primingLeft = 0;
        fadeNext = true;
        return true;
    }
    void frame(double stamp, double arrival) {
        double raw = arrival - stamp;
        if (!Double.isFinite(raw)) return;
        frameArrivals.add(arrival);
        frameRaws.add(raw);
        sortedRaws.add(insertionIndex(sortedRaws, raw), raw);
        expireFrames(arrival);
    }
    void latency(double output, double io, double refresh, double streamFPS) {
        outputLatency = nonnegative(output);
        ioBuffer = nonnegative(io);
        this.refresh = nonnegative(refresh);
        this.streamFPS = Double.isFinite(streamFPS) && streamFPS > 0 ? streamFPS : 60;
    }
    void rtt(Double median) { rttMedian = median != null && Double.isFinite(median) && median >= 0 ? median : null; }
    void away(boolean value) { away = value; }
    void mute(boolean value) { muted = value; }
    Output played(double sample, double time, double now) {
        Output out = new Output();
        if (!Double.isFinite(sample) || !Double.isFinite(time)) return out;
        boolean first = mappingSample == null;
        mappingSample = sample;
        mappingTime = time;
        if (first && !waiting.isEmpty()) {
            List<Admitted> list = new ArrayList<>(waiting);
            waiting.clear();
            for (Admitted a : list) out.add(place(a, now));
        }
        return out;
    }
    Plan packet(int epoch, long seq, double stamp, boolean segmentStart, double now) {
        return packet(epoch, seq, stamp, segmentStart, now, now);
    }
    Plan packet(int epoch, long seq, double stamp, boolean segmentStart, double arrival, double now) {
        Plan plan = new Plan();
        seq &= 0xffffffffL;
        if (this.epoch == null || epoch != this.epoch || !Double.isFinite(stamp) || !Double.isFinite(arrival)) return plan;
        long key = ((long) (epoch & 0xffff) << 32) | seq;
        if (recentSet.contains(key)) { second.duplicates++; return plan; }
        remember(key);
        record(arrival - stamp, arrival);
        if (muted) { expectedSeq = null; return plan; }
        plan.decode = true;
        boolean continuous = !segmentStart && expectedSeq != null && expectedSeq == seq;
        expectedSeq = increment(seq);
        if (segmentStart) {
            plan.resetFirst = true;
            primingLeft = primingFrames;
            fadeNext = true;
        } else if (!continuous) {
            plan.resetFirst = true;
            plan.dropFront = framesPerPacket;
            primingLeft = 0;
            fadeNext = true;
            second.joins++;
            plan.output = handOver(true);
            return plan;
        }
        int drop = Math.min(primingLeft, framesPerPacket);
        primingLeft -= drop;
        plan.dropFront = drop;
        int kept = framesPerPacket - drop;
        if (kept <= 0) return plan;
        Admitted a = new Admitted(nextID++, epoch, seq, stamp + drop / RATE, kept, fadeNext, segmentStart);
        fadeNext = false;
        plan.id = a.id;
        if (mappingSample == null) {
            waiting.add(a);
            if (waiting.size() > 100) {
                plan.output.discards.add(waiting.remove(0).id);
                Admitted f = waiting.get(0);
                waiting.set(0, new Admitted(f.id, f.epoch, f.seq, f.stamp, f.frames, true, f.segmentStart));
            }
        } else plan.output.add(place(a, now));
        return plan;
    }
    void decodeFailed() { expectedSeq = null; primingLeft = 0; fadeNext = true; }
    Output tick(double now) {
        Double deadline = deadline();
        return deadline != null && now >= deadline ? handOver(true) : new Output();
    }
    Double deadline() { return hand == null ? null : time(hand.tailStart()) - ioBuffer - MARGIN; }
    boolean holding() { return hand != null; }
    Output reset(Reset kind) {
        if (kind == Reset.JUMP) { owe(JumpCause.ROUTE); return new Output(); }
        Output out = new Output();
        if (hand != null) out.discards.add(hand.packet.id);
        for (Admitted a : waiting) out.discards.add(a.id);
        hand = null; waiting.clear(); chainEnd = null; mappingSample = mappingTime = null;
        jumpOwed = false; owedCause = null; madeJump = null; correcting = 0;
        expectedSeq = null; primingLeft = 0; fadeNext = true;
        if (kind == Reset.SESSION) {
            epoch = null; cookie = new byte[0]; buckets.clear(); floorKept = sessionStart = null;
            above = null; clearFrames(); recentKeys.clear(); recentSet.clear(); recentNext = 0;
            lastError = null; placedThisSession = false; second = new Second(); behind.clear();
        }
        return out;
    }
    Second takeSecond(double now) {
        expireFrames(now);
        Second s = second;
        behind.sort(Double::compare);
        s.behindMs = behind.isEmpty() ? null : (int) rounded(median(behind) * 1000);
        s.need = need(now); s.cover = cover(now); s.lag = pictureLag(now);
        s.delay = delay(now, s.lag); s.error = lastError;
        second = new Second(); behind.clear();
        return s;
    }
    Double floor() {
        if (buckets.isEmpty()) return floorKept;
        double value = Double.POSITIVE_INFINITY;
        for (Bucket b : buckets) value = Math.min(value, b.min);
        return value;
    }
    double cover(double now) {
        double start = away ? .150 : .040;
        Double floor = floor(), top = null;
        if (sessionStart == null || now - sessionStart < 2 || floor == null) return start;
        for (Bucket b : buckets) if (b.max != null) top = top == null ? b.max : Math.max(top, b.max);
        if (top == null) return start;
        return Math.min(Math.max(top - floor, away ? .060 : .020), away ? .600 : .150);
    }
    double need(double now) { return cover(now) + framesPerPacket / RATE + ioBuffer + outputLatency + MARGIN; }
    Double pictureLag(double now) {
        Double floor = floor();
        if (floor == null) return null;
        Double raw = !sortedRaws.isEmpty() && liveFrames(now) > 0 ? Double.valueOf(median(sortedRaws)) : lastPictureRaw;
        return raw == null ? null : raw - floor + 1.5 * refresh + .004;
    }
    double delay(double now, Double lag) { return Math.max(need(now), lag == null ? Double.NEGATIVE_INFINITY : lag + 1 / streamFPS); }
    private Output place(Admitted a, double now) {
        Output out = new Output();
        if (deadline() != null && now >= deadline()) out.add(handOver(true));
        Double floor = floor();
        if (floor == null) return out;
        expireFrames(now);
        Double lag = pictureLag(now);
        double due = a.stamp + floor + delay(now, lag);
        double picture = lag == null ? Double.NEGATIVE_INFINITY : a.stamp + floor + lag;
        Planned placed = null;
        if (hand != null && !jumpOwed && hand.takes(a)) {
            double start = hand.end(), ear = time(start) + outputLatency;
            Decision decision = new Decision(due, ear, picture);
            lastError = decision.error();
            if (ear >= picture && Math.abs(decision.error()) <= .040) {
                int change = a.frames - Math.min(FADE_FRAMES, a.frames) >= 4 ? correction(decision.error()) : 0;
                placed = new Planned(a, start, true, change, false, decision);
            } else owe(ear < picture ? JumpCause.AHEAD : JumpCause.ERROR);
        }
        if (placed == null) {
            double start = sample(due - outputLatency);
            Double chain = hand != null ? Double.valueOf(hand.end()) : chainEnd;
            if (jumpOwed && owedCause != null && chain != null) {
                madeJump = new Jump((start - chain) / RATE, owedCause);
                owedCause = null;
            }
            if (chain != null && start < chain) {
                if (jumpOwed && chain - start > framesPerPacket / 2.0) {
                    second.jumpDrops++;
                    out.add(handOver(true)); out.discards.add(a.id); return out;
                }
                start = chain;
            }
            if (time(start) - now < ioBuffer + MARGIN) {
                second.late++; out.add(handOver(true)); out.discards.add(a.id); return out;
            }
            jumpOwed = false; correcting = 0;
            placed = new Planned(a, start, false, 0, true, new Decision(due, time(start) + outputLatency, picture));
        }
        if (hand != null) out.schedules.add(hand.tail(!placed.contiguous, time(hand.tailStart())));
        Schedule body = placed.body(time(placed.start));
        if (body != null) out.schedules.add(body);
        hand = placed; chainEnd = null; second.placed++; placedThisSession = true;
        if (placed.change > 0) second.framesAdded++; else if (placed.change < 0) second.framesDropped++;
        if (placed.decision.picture > Double.NEGATIVE_INFINITY) behind.add(placed.decision.ear - placed.decision.picture);
        return out;
    }
    private Output handOver(boolean fadeOut) {
        Output out = new Output();
        if (hand == null) return out;
        Planned h = hand; hand = null; chainEnd = h.end();
        out.schedules.add(h.tail(fadeOut, time(h.tailStart())));
        return out;
    }
    private void owe(JumpCause cause) {
        if (jumpOwed) return;
        jumpOwed = true;
        if (hand != null || chainEnd != null) { second.jumps++; owedCause = cause; }
    }
    Jump takeJump() { Jump result = madeJump; madeJump = null; return result; }
    private int correction(double error) {
        if (correcting != 0 && Math.abs(error) < .00025) correcting = 0;
        else if (error > .001) correcting = 1;
        else if (error < -.001) correcting = -1;
        else if (correcting != 0 && (error > 0) != (correcting > 0)) correcting = 0;
        return correcting;
    }
    private double time(double sample) { return mappingSample == null ? Double.NEGATIVE_INFINITY : mappingTime + (sample - mappingSample) / RATE; }
    private double sample(double time) { return mappingSample == null ? 0 : rounded(mappingSample + (time - mappingTime) * RATE); }
    private void record(double raw, double arrival) {
        if (sessionStart == null) sessionStart = arrival;
        long now = (long) Math.floor(arrival);
        buckets.removeIf(b -> b.second <= now - 10);
        Double floor = floor();
        if (floor != null) {
            if (raw < floor - .020) { restart(new Bucket(now, raw, coverValue(raw, arrival))); return; }
            if (raw - floor > Math.max(.050, 2 * (rttMedian == null ? 0 : rttMedian))) {
                if (above == null) above = new Above(arrival, raw);
                above.count++; above.min = Math.min(above.min, raw); above.max = Math.max(above.max, raw);
                if (arrival - above.since >= 1 && above.count >= 50) {
                    restart(new Bucket(now, above.min, coverValue(above.max, arrival))); return;
                }
            } else above = null;
        }
        if (buckets.isEmpty() || buckets.get(buckets.size() - 1).second != now) buckets.add(new Bucket(now, raw, null));
        Bucket b = buckets.get(buckets.size() - 1);
        b.min = Math.min(b.min, raw);
        Double max = coverValue(raw, arrival);
        if (max != null) b.max = b.max == null ? max : Math.max(b.max, max);
        floorKept = floor();
    }
    private Double coverValue(double raw, double arrival) { return sessionStart != null && arrival - sessionStart >= 2 ? raw : null; }
    private void restart(Bucket b) {
        buckets.clear(); buckets.add(b); floorKept = b.min; above = null; clearFrames(); owe(JumpCause.STEP);
    }
    private void clearFrames() { frameArrivals.clear(); frameRaws.clear(); sortedRaws.clear(); frameHead = 0; lastPictureRaw = null; }
    private void remember(long key) {
        if (recentKeys.size() < 256) recentKeys.add(key);
        else { recentSet.remove(recentKeys.set(recentNext, key)); recentNext = (recentNext + 1) % 256; }
        recentSet.add(key);
    }
    private void expireFrames(double now) {
        double from = now - 2;
        while (frameHead < frameArrivals.size() && frameArrivals.get(frameHead) < from) {
            double raw = frameRaws.get(frameHead);
            if (sortedRaws.size() == 1) lastPictureRaw = sortedRaws.get(0);
            int i = insertionIndex(sortedRaws, raw);
            if (i < sortedRaws.size() && sortedRaws.get(i) == raw) sortedRaws.remove(i);
            else if (i > 0 && sortedRaws.get(i - 1) == raw) sortedRaws.remove(i - 1);
            frameHead++;
        }
        if (frameHead > 512) {
            frameArrivals.subList(0, frameHead).clear(); frameRaws.subList(0, frameHead).clear(); frameHead = 0;
        }
        if (!sortedRaws.isEmpty()) lastPictureRaw = median(sortedRaws);
    }
    private int liveFrames(double now) {
        int n = 0;
        for (int i = frameArrivals.size() - 1; i >= frameHead && frameArrivals.get(i) >= now - 2; i--) n++;
        return n;
    }
    private static int insertionIndex(List<Double> list, double value) {
        int lo = 0, hi = list.size();
        while (lo < hi) { int mid = (lo + hi) / 2; if (list.get(mid) < value) lo = mid + 1; else hi = mid; }
        return lo;
    }
    private static double median(List<Double> values) {
        int n = values.size(), mid = n / 2;
        return n == 0 ? 0 : n % 2 == 1 ? values.get(mid) : (values.get(mid - 1) + values.get(mid)) / 2;
    }
    private static double nonnegative(double value) { return Double.isFinite(value) ? Math.max(0, value) : 0; }
    private static long increment(long seq) { return (seq + 1) & 0xffffffffL; }
    private static double rounded(double value) { return Math.copySign(Math.floor(Math.abs(value) + .5), value); }
    static void fade(float[] x, boolean fadeIn, int frames) {
        int n = Math.min(frames, x.length);
        for (int i = 0; i < n; i++) {
            float g = (float) (.5 - .5 * Math.cos(Math.PI * (i + 1) / (n + 1)));
            x[fadeIn ? i : x.length - 1 - i] *= g;
        }
    }
    /** Returns two replacement channel arrays; a frame edit always uses the same index in both. */
    static float[][] change(float[] left, float[] right, int change) {
        int n = Math.min(left.length, right.length);
        if (change == 0 || n < 4) return new float[][] {left.clone(), right.clone()};
        int from = n / 4, to = Math.max(from + 1, 3 * n / 4), q = from;
        float level = Float.POSITIVE_INFINITY;
        for (int i = from; i < Math.min(to, n - 1); i++) {
            float l = Math.abs(left[i]) + Math.abs(right[i]) + Math.abs(left[i + 1]) + Math.abs(right[i + 1]);
            if (l < level) { level = l; q = i; }
        }
        return new float[][] {edit(left, q, change), edit(right, q, change)};
    }
    private static float[] edit(float[] x, int q, int change) {
        float[] result = new float[x.length + (change > 0 ? 1 : -1)];
        System.arraycopy(x, 0, result, 0, q + 1);
        if (change > 0) {
            result[q + 1] = (x[q] + x[q + 1]) / 2;
            System.arraycopy(x, q + 1, result, q + 2, x.length - q - 1);
        } else {
            result[q] = (x[q] + x[q + 1]) / 2;
            System.arraycopy(x, q + 2, result, q + 1, x.length - q - 2);
        }
        return result;
    }
}
