/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.app.AlarmManager;
import android.os.Handler;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import me.sandai.dashled.aidl.DashLedEffect;
import me.sandai.dashled.aidl.IDashLedManager;

/**
 * Session registry + priority arbitration. The highest-category session that
 * currently holds content drives the hardware; when it clears or dies the
 * next one is restored. Effects are rendered here on the LED thread so
 * clients never stream frames for built-in-style animations.
 *
 * External lock order: all public methods are synchronized; hardware writes
 * go through the backend's own serialized handler. Effect completion
 * callbacks always run after the arbiter lock is released, so callbacks may
 * re-enter the arbiter.
 */
public final class LedArbiter {

    private final Aw21024Backend mBackend;
    private final Handler mHandler;
    private final AlarmManager mAlarmManager;
    private final LongSupplier mElapsedRealtime;
    private final List<Session> mSessions = new ArrayList<>();

    private boolean mOutputEnabled = true;
    private long mNextSeq;
    private Session mRendered;
    private long mRenderedSeq = -1;
    private EffectRunner mEffectRunner;
    private BpcCompletion mBpcDone;
    private NotificationPulse mNotification;

    public final class Session {
        private final int category;
        private long seq;
        private int[] colors;
        private int brightness;
        private DashLedEffect effect;
        private Runnable onEffectDone;
        private boolean released;

        private Session(int category) {
            this.category = category;
        }
    }

    public LedArbiter(Aw21024Backend backend, Handler handler, AlarmManager alarmManager) {
        this(backend, handler, alarmManager, SystemClock::elapsedRealtime);
    }

    LedArbiter(Aw21024Backend backend, Handler handler, AlarmManager alarmManager,
            LongSupplier elapsedRealtime) {
        mBackend = backend;
        mHandler = handler;
        mAlarmManager = alarmManager;
        mElapsedRealtime = elapsedRealtime;
    }

    public synchronized Session acquire(int category) {
        Session session = new Session(category);
        mSessions.add(session);
        return session;
    }

    public synchronized void setFrame(Session session, int[] colors, int brightness) {
        checkLive(session);
        session.colors = colors.clone();
        session.brightness = brightness;
        session.effect = null;
        session.onEffectDone = null;
        session.seq = mNextSeq++;
        renderLocked();
    }

    public synchronized void playEffect(Session session, DashLedEffect effect,
            Runnable onDone) {
        checkLive(session);
        session.effect = effect;
        session.onEffectDone = onDone;
        session.seq = mNextSeq++;
        renderLocked();
    }

    synchronized void playNotification(DashLedEffect effect) {
        if (!mOutputEnabled) {
            return;
        }
        discardNotificationLocked();
        Session session = acquire(IDashLedManager.CATEGORY_NOTIFICATION);
        session.effect = effect;
        session.seq = mNextSeq++;
        NotificationPulse pulse = new NotificationPulse(session);
        mNotification = pulse;
        pulse.scheduleExpiryLocked();
        renderLocked();
        // Only the initial render may start a full hardware pattern.
        pulse.allowFullFirstPattern = false;
    }

    public synchronized void clear(Session session) {
        if (!mSessions.contains(session)) {
            return;
        }
        session.colors = null;
        session.effect = null;
        session.onEffectDone = null;
        renderLocked();
    }

    public synchronized void release(Session session) {
        if (session.released) {
            return;
        }
        session.released = true;
        mSessions.remove(session);
        renderLocked();
    }

    /** Master switch. Re-enabling restores other content, but not a notification pulse. */
    public synchronized void setOutputEnabled(boolean enabled) {
        if (mOutputEnabled == enabled) {
            return;
        }
        mOutputEnabled = enabled;
        if (!enabled) {
            discardNotificationLocked();
        }
        renderLocked();
    }

    private void checkLive(Session session) {
        if (session.released || !mSessions.contains(session)) {
            throw new IllegalStateException("session released");
        }
    }

    private void renderLocked() {
        if (mNotification != null
                && mElapsedRealtime.getAsLong() >= mNotification.deadlineElapsedMs) {
            discardNotificationLocked();
        }
        Session active = null;
        for (Session session : mSessions) {
            if (session.colors == null && session.effect == null) {
                continue;
            }
            if (active == null || session.category > active.category
                    || (session.category == active.category && session.seq > active.seq)) {
                active = session;
            }
        }

        if (!mOutputEnabled || active == null) {
            stopEffectLocked();
            if (mRendered != null) {
                mBackend.submitOff();
            }
            mRendered = null;
            mRenderedSeq = -1;
            return;
        }

        if (active == mRendered && active.seq == mRenderedSeq) {
            return;
        }

        stopEffectLocked();
        mRendered = active;
        mRenderedSeq = active.seq;
        if (mNotification != null && active == mNotification.session) {
            mNotification.renderLocked();
        } else if (active.effect != null) {
            if (isBpcCompatible(active.effect)) {
                playBpcLocked(active);
            } else {
                mEffectRunner = new EffectRunner(active);
                mHandler.post(mEffectRunner);
            }
        } else {
            mBackend.submitFrame(active.colors, active.brightness);
        }
    }

    /** The chip runs a single shared timing envelope, so any triangle breath
     *  offloads; per-zone colors go through the per-channel COL path. */
    private static boolean isBpcCompatible(DashLedEffect effect) {
        if (effect.type != DashLedEffect.TYPE_BREATH || effect.repeatCount > 255) {
            return false;
        }
        return Aw21024Backend.isBreathHardwareCompatible(effect.periodMs,
                effect.brightness);
    }

    private void playBpcLocked(Session session) {
        DashLedEffect effect = session.effect;
        final BpcCompletion done = new BpcCompletion(session);
        mBpcDone = done;
        mBackend.submitBpcBreath(effect.colors, effect.brightness,
                effect.periodMs, effect.repeatCount, () -> {
                    synchronized (LedArbiter.this) {
                        if (effect.repeatCount > 0 && mBpcDone == done) {
                            long duration = Aw21024Backend.estimateBpcDurationMs(
                                    effect.periodMs, effect.repeatCount);
                            done.deadlineElapsedMs = mElapsedRealtime.getAsLong() + duration;
                            // Handler retains sub-five-second precision while awake;
                            // AlarmManager applies a five-second minimum for app UIDs.
                            mHandler.postDelayed(done, duration);
                            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME,
                                    done.deadlineElapsedMs, "DashLed:breath", done, mHandler);
                        }
                    }
                }, done);
    }

    /** Reconcile on screen-on too: Android may drop listener alarms when cached. */
    public void onWake() {
        final BpcCompletion done;
        synchronized (this) {
            if (mNotification != null
                    && mElapsedRealtime.getAsLong() >= mNotification.deadlineElapsedMs) {
                renderLocked();
            }
            done = mBpcDone;
            if (done == null || done.deadlineElapsedMs == 0
                    || mElapsedRealtime.getAsLong() < done.deadlineElapsedMs) {
                return;
            }
        }
        done.run();
    }

    private final class BpcCompletion implements Runnable, AlarmManager.OnAlarmListener {
        private final Session session;
        private long deadlineElapsedMs;

        BpcCompletion(Session session) {
            this.session = session;
        }

        @Override
        public void onAlarm() {
            run();
        }

        @Override
        public void run() {
            final Runnable completed;
            synchronized (LedArbiter.this) {
                if (mBpcDone != this) {
                    return;
                }
                completed = session.onEffectDone;
                // clear() cancels both completion paths before restoring another session.
                clear(session);
            }
            if (completed != null) {
                completed.run();
            }
        }
    }

    private void discardNotificationLocked() {
        if (mNotification == null) {
            return;
        }
        mHandler.removeCallbacks(mNotification);
        mAlarmManager.cancel(mNotification);
        mSessions.remove(mNotification.session);
        mNotification = null;
    }

    private void stopEffectLocked() {
        if (mNotification != null && mRendered == mNotification.session) {
            mNotification.renderGeneration++;
        }
        if (mEffectRunner != null) {
            mHandler.removeCallbacks(mEffectRunner);
            mEffectRunner = null;
        }
        if (mBpcDone != null) {
            mHandler.removeCallbacks(mBpcDone);
            if (mBpcDone.deadlineElapsedMs != 0) {
                mAlarmManager.cancel(mBpcDone);
            }
            mBpcDone = null;
        }
    }

    /** The built-in pulse has one elapsed-time budget, even while hidden by the camera. */
    private final class NotificationPulse implements Runnable, AlarmManager.OnAlarmListener {
        private final Session session;
        private final long cycleMs;
        private final long durationMs;
        private long startElapsedMs;
        private long deadlineElapsedMs;
        private int renderGeneration;
        private boolean allowFullFirstPattern = true;

        NotificationPulse(Session session) {
            this.session = session;
            DashLedEffect effect = session.effect;
            cycleMs = Aw21024Backend.estimateBpcCycleMs(effect.periodMs);
            durationMs = Aw21024Backend.estimateBpcDurationMs(effect.periodMs, effect.repeatCount);
            startElapsedMs = mElapsedRealtime.getAsLong();
        }

        void scheduleExpiryLocked() {
            mHandler.removeCallbacks(this);
            if (deadlineElapsedMs != 0) {
                mAlarmManager.cancel(this);
            }
            deadlineElapsedMs = startElapsedMs + durationMs;
            mHandler.postDelayed(this,
                    Math.max(0, deadlineElapsedMs - mElapsedRealtime.getAsLong()));
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME,
                    deadlineElapsedMs, "DashLed:notification", this, mHandler);
        }

        void renderLocked() {
            DashLedEffect effect = session.effect;
            int generation = ++renderGeneration;
            Runnable failed = () -> {
                synchronized (LedArbiter.this) {
                    if (isCurrentRender(generation)) {
                        discardNotificationLocked();
                        LedArbiter.this.renderLocked();
                    }
                }
            };
            boolean initial = allowFullFirstPattern;
            // BPC cannot seek. On resume, fit the remaining cycle slots into
            // the original visible window, excluding its 500 ms cleanup margin.
            mBackend.submitBpcBreathWithin(effect.colors, effect.brightness,
                    effect.periodMs, effect.repeatCount, () -> {
                        synchronized (LedArbiter.this) {
                            long now = mElapsedRealtime.getAsLong();
                            if (!isCurrentRender(generation) || now >= deadlineElapsedMs) {
                                return 0;
                            }
                            long visibleMs = cycleMs * effect.repeatCount;
                            return initial ? visibleMs : startElapsedMs + visibleMs - now;
                        }
                    }, initial ? () -> {
                        synchronized (LedArbiter.this) {
                            if (isCurrentRender(generation)) {
                                startElapsedMs = mElapsedRealtime.getAsLong();
                                scheduleExpiryLocked();
                            }
                        }
                    } : null, failed);
        }

        private boolean isCurrentRender(int generation) {
            return mNotification == this && mRendered == session
                    && renderGeneration == generation;
        }

        @Override
        public void onAlarm() {
            run();
        }

        @Override
        public void run() {
            synchronized (LedArbiter.this) {
                if (mNotification != this) {
                    return;
                }
                long remaining = deadlineElapsedMs - mElapsedRealtime.getAsLong();
                if (remaining <= 0) {
                    LedArbiter.this.renderLocked();
                } else {
                    // A completion queued before the first hardware start can still arrive.
                    mHandler.removeCallbacks(this);
                    mHandler.postDelayed(this, remaining);
                }
            }
        }
    }

    /** Triangle-wave brightness loop stepping at the backend coalescing rate. */
    private final class EffectRunner implements Runnable {
        private final Session mSession;
        private final int mStepsPerCycle;
        private int mStep;
        private int mCycle;

        EffectRunner(Session session) {
            mSession = session;
            mStepsPerCycle = Math.max(2,
                    session.effect.periodMs / Aw21024Backend.MIN_FRAME_INTERVAL_MS);
        }

        @Override
        public void run() {
            final Runnable done;
            synchronized (LedArbiter.this) {
                if (mEffectRunner != this || mSession.effect == null) {
                    return;
                }
                DashLedEffect effect = mSession.effect;
                float phase = (float) mStep / mStepsPerCycle;
                float triangle = phase < 0.5f ? phase * 2f : (1f - phase) * 2f;
                int level = Math.round(effect.brightness * triangle);
                mBackend.submitFrame(effect.colors, level);

                Runnable completed = null;
                if (++mStep >= mStepsPerCycle) {
                    mStep = 0;
                    if (effect.repeatCount > 0 && ++mCycle >= effect.repeatCount) {
                        completed = mSession.onEffectDone;
                        clear(mSession);
                    }
                }
                done = completed;
                if (mEffectRunner == this) {
                    mHandler.postDelayed(this, Aw21024Backend.MIN_FRAME_INTERVAL_MS);
                }
            }
            if (done != null) {
                done.run();
            }
        }
    }
}
