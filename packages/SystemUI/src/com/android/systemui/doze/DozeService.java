/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.doze;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Vibrator;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.Display;

import com.android.systemui.SystemUIApplication;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.DozeParameters.PulseSchedule;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Date;

public class DozeService extends DreamService {
    private static final String TAG = "DozeService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String ACTION_BASE = "com.android.systemui.doze";
    private static final String PULSE_ACTION = ACTION_BASE + ".pulse";
    private static final String NOTIFICATION_PULSE_ACTION = ACTION_BASE + ".notification_pulse";

    private final String mTag = String.format(TAG + ".%08x", hashCode());
    private final Context mContext = this;
    private final Handler mHandler = new Handler();
    private final DozeParameters mDozeParameters = new DozeParameters(mContext);

    private Host mHost;
    private SensorManager mSensors;
    private TriggerSensor mSigMotionSensor;
    private TriggerSensor mPickupSensor;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private AlarmManager mAlarmManager;
    private boolean mDreaming;
    private boolean mBroadcastReceiverRegistered;
    private boolean mDisplayStateSupported;
    private int mDisplayStateWhenOn;
    private boolean mNotificationLightOn;
    private PendingIntent mNotificationPulseIntent;
    private boolean mPowerSaveActive;
    private long mNotificationPulseTime;
    private int mScheduleResetsRemaining;

    public DozeService() {
        if (DEBUG) Log.d(mTag, "new DozeService()");
        setDebug(DEBUG);
    }

    @Override
    protected void dumpOnHandler(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dumpOnHandler(fd, pw, args);
        pw.print("  mDreaming: "); pw.println(mDreaming);
        pw.print("  mHost: "); pw.println(mHost);
        pw.print("  mBroadcastReceiverRegistered: "); pw.println(mBroadcastReceiverRegistered);
        pw.print("  mSigMotionSensor: "); pw.println(mSigMotionSensor);
        pw.print("  mPickupSensor:"); pw.println(mPickupSensor);
        pw.print("  mDisplayStateSupported: "); pw.println(mDisplayStateSupported);
        pw.print("  mNotificationLightOn: "); pw.println(mNotificationLightOn);
        pw.print("  mPowerSaveActive: "); pw.println(mPowerSaveActive);
        pw.print("  mNotificationPulseTime: "); pw.println(mNotificationPulseTime);
        pw.print("  mScheduleResetsRemaining: "); pw.println(mScheduleResetsRemaining);
        mDozeParameters.dump(pw);
    }

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(mTag, "onCreate");
        super.onCreate();

        if (getApplication() instanceof SystemUIApplication) {
            final SystemUIApplication app = (SystemUIApplication) getApplication();
            mHost = app.getComponent(Host.class);
        }
        if (mHost == null) Log.w(TAG, "No doze service host found.");

        setWindowless(true);

        mSensors = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mSigMotionSensor = new TriggerSensor(Sensor.TYPE_SIGNIFICANT_MOTION,
                mDozeParameters.getPulseOnSigMotion(), mDozeParameters.getVibrateOnSigMotion());
        mPickupSensor = new TriggerSensor(Sensor.TYPE_PICK_UP_GESTURE,
                mDozeParameters.getPulseOnPickup(), mDozeParameters.getVibrateOnPickup());
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, mTag);
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mDisplayStateSupported = mDozeParameters.getDisplayStateSupported();
        mNotificationPulseIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(NOTIFICATION_PULSE_ACTION).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT);
        mDisplayStateWhenOn = mDisplayStateSupported ? Display.STATE_DOZE : Display.STATE_ON;
        mDisplayOff.run();
    }

    @Override
    public void onAttachedToWindow() {
        if (DEBUG) Log.d(mTag, "onAttachedToWindow");
        super.onAttachedToWindow();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        mPowerSaveActive = mHost != null && mHost.isPowerSaveActive();
        if (DEBUG) Log.d(mTag, "onDreamingStarted canDoze=" + canDoze() + " mPowerSaveActive="
                + mPowerSaveActive);
        if (mPowerSaveActive) {
            finishToSavePower();
            return;
        }
        mDreaming = true;
        listenForPulseSignals(true);
        rescheduleNotificationPulse(false /*predicate*/);  // cancel any pending pulse alarms
        requestDoze();
    }

    public void stayAwake(long millis) {
        if (mDreaming && millis > 0) {
            if (DEBUG) Log.d(mTag, "stayAwake millis=" + millis);
            mWakeLock.acquire(millis);
            setDozeScreenState(mDisplayStateWhenOn);
            rescheduleOff(millis);
        }
    }

    private void rescheduleOff(long millis) {
        if (DEBUG) Log.d(TAG, "rescheduleOff millis=" + millis);
        mHandler.removeCallbacks(mDisplayOff);
        mHandler.postDelayed(mDisplayOff, millis);
    }

    @Override
    public void onDreamingStopped() {
        if (DEBUG) Log.d(mTag, "onDreamingStopped isDozing=" + isDozing());
        super.onDreamingStopped();

        mDreaming = false;
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        listenForPulseSignals(false);
        dozingStopped();
        mHandler.removeCallbacks(mDisplayOff);
    }

    @Override
    public void startDozing() {
        if (DEBUG) Log.d(mTag, "startDozing");
        super.startDozing();
    }

    private void requestDoze() {
        if (mHost != null) {
            mHost.requestDoze(this);
        }
    }

    private void requestPulse() {
        if (mHost != null) {
            mHost.requestPulse(this);
        }
    }

    private void dozingStopped() {
        if (mHost != null) {
            mHost.dozingStopped(this);
        }
    }

    private void finishToSavePower() {
        Log.w(mTag, "Exiting ambient mode due to low power battery saver");
        finish();
    }

    private void listenForPulseSignals(boolean listen) {
        if (DEBUG) Log.d(mTag, "listenForPulseSignals: " + listen);
        mSigMotionSensor.setListening(listen);
        mPickupSensor.setListening(listen);
        listenForBroadcasts(listen);
        listenForNotifications(listen);
    }

    private void listenForBroadcasts(boolean listen) {
        if (listen) {
            final IntentFilter filter = new IntentFilter(PULSE_ACTION);
            filter.addAction(NOTIFICATION_PULSE_ACTION);
            mContext.registerReceiver(mBroadcastReceiver, filter);
            mBroadcastReceiverRegistered = true;
        } else {
            if (mBroadcastReceiverRegistered) {
                mContext.unregisterReceiver(mBroadcastReceiver);
            }
            mBroadcastReceiverRegistered = false;
        }
    }

    private void listenForNotifications(boolean listen) {
        if (mHost == null) return;
        if (listen) {
            resetNotificationResets();
            mHost.addCallback(mHostCallback);
        } else {
            mHost.removeCallback(mHostCallback);
        }
    }

    private void resetNotificationResets() {
        if (DEBUG) Log.d(TAG, "resetNotificationResets");
        mScheduleResetsRemaining = mDozeParameters.getPulseScheduleResets();
    }

    private void updateNotificationPulse() {
        if (DEBUG) Log.d(TAG, "updateNotificationPulse");
        if (!mDozeParameters.getPulseOnNotifications()) return;
        if (mScheduleResetsRemaining <= 0) {
            if (DEBUG) Log.d(TAG, "No more schedule resets remaining");
            return;
        }
        final long now = System.currentTimeMillis();
        if ((now - mNotificationPulseTime) < mDozeParameters.getPulseDuration()) {
            if (DEBUG) Log.d(TAG, "Recently updated, not resetting schedule");
            return;
        }
        mScheduleResetsRemaining--;
        if (DEBUG) Log.d(TAG, "mScheduleResetsRemaining = " + mScheduleResetsRemaining);
        mNotificationPulseTime = now;
        rescheduleNotificationPulse(true /*predicate*/);
    }

    private void rescheduleNotificationPulse(boolean predicate) {
        if (DEBUG) Log.d(TAG, "rescheduleNotificationPulse predicate=" + predicate);
        mAlarmManager.cancel(mNotificationPulseIntent);
        if (!predicate) {
            if (DEBUG) Log.d(TAG, "  don't reschedule: predicate is false");
            return;
        }
        final PulseSchedule schedule = mDozeParameters.getPulseSchedule();
        if (schedule == null) {
            if (DEBUG) Log.d(TAG, "  don't reschedule: schedule is null");
            return;
        }
        final long now = System.currentTimeMillis();
        final long time = schedule.getNextTime(now, mNotificationPulseTime);
        if (time <= 0) {
            if (DEBUG) Log.d(TAG, "  don't reschedule: time is " + time);
            return;
        }
        final long delta = time - now;
        if (delta <= 0) {
            if (DEBUG) Log.d(TAG, "  don't reschedule: delta is " + delta);
            return;
        }
        if (DEBUG) Log.d(TAG, "Scheduling pulse in " + delta + "ms for " + new Date(time));
        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, time, mNotificationPulseIntent);
    }

    private static String triggerEventToString(TriggerEvent event) {
        if (event == null) return null;
        final StringBuilder sb = new StringBuilder("TriggerEvent[")
                .append(event.timestamp).append(',')
                .append(event.sensor.getName());
        if (event.values != null) {
            for (int i = 0; i < event.values.length; i++) {
                sb.append(',').append(event.values[i]);
            }
        }
        return sb.append(']').toString();
    }

    private final Runnable mDisplayOff = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "Display off");
            setDozeScreenState(Display.STATE_OFF);
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PULSE_ACTION.equals(intent.getAction())) {
                if (DEBUG) Log.d(mTag, "Received pulse intent");
                requestPulse();
            }
            if (NOTIFICATION_PULSE_ACTION.equals(intent.getAction())) {
                if (DEBUG) Log.d(mTag, "Received notification pulse intent");
                requestPulse();
                rescheduleNotificationPulse(mNotificationLightOn);
            }
        }
    };

    private final Host.Callback mHostCallback = new Host.Callback() {
        @Override
        public void onNewNotifications() {
            if (DEBUG) Log.d(mTag, "onNewNotifications");
            // noop for now
        }

        @Override
        public void onBuzzBeepBlinked() {
            if (DEBUG) Log.d(mTag, "onBuzzBeepBlinked");
            updateNotificationPulse();
        }

        @Override
        public void onNotificationLight(boolean on) {
            if (DEBUG) Log.d(mTag, "onNotificationLight on=" + on);
            if (mNotificationLightOn == on) return;
            mNotificationLightOn = on;
            if (mNotificationLightOn) {
                updateNotificationPulse();
            }
        }

        @Override
        public void onPowerSaveChanged(boolean active) {
            mPowerSaveActive = active;
            if (mPowerSaveActive && mDreaming) {
                finishToSavePower();
            }
        }
    };

    public interface Host {
        void addCallback(Callback callback);
        void removeCallback(Callback callback);
        void requestDoze(DozeService dozeService);
        void requestPulse(DozeService dozeService);
        void dozingStopped(DozeService dozeService);
        boolean isPowerSaveActive();

        public interface Callback {
            void onNewNotifications();
            void onBuzzBeepBlinked();
            void onNotificationLight(boolean on);
            void onPowerSaveChanged(boolean active);
        }
    }

    private class TriggerSensor extends TriggerEventListener {
        private final Sensor mSensor;
        private final boolean mConfigured;
        private final boolean mDebugVibrate;

        private boolean mEnabled;

        public TriggerSensor(int type, boolean configured, boolean debugVibrate) {
            mSensor = mSensors.getDefaultSensor(type);
            mConfigured = configured;
            mDebugVibrate = debugVibrate;
        }

        public void setListening(boolean listen) {
            if (!mConfigured || mSensor == null) return;
            if (listen) {
                mEnabled = mSensors.requestTriggerSensor(this, mSensor);
            } else if (mEnabled) {
                mSensors.cancelTriggerSensor(this, mSensor);
                mEnabled = false;
            }
        }

        @Override
        public String toString() {
            return new StringBuilder("{mEnabled=").append(mEnabled).append(", mConfigured=")
                    .append(mConfigured).append(", mDebugVibrate=").append(mDebugVibrate)
                    .append(", mSensor=").append(mSensor).append("}").toString();
        }

        @Override
        public void onTrigger(TriggerEvent event) {
            if (DEBUG) Log.d(mTag, "onTrigger: " + triggerEventToString(event));
            if (mDebugVibrate) {
                final Vibrator v = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    v.vibrate(1000, new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build());
                }
            }
            requestPulse();
            setListening(true);  // reregister, this sensor only fires once

            // reset the notification pulse schedule, but only if we think we were not triggered
            // by a notification-related vibration
            final long timeSinceNotification = System.currentTimeMillis() - mNotificationPulseTime;
            if (timeSinceNotification < mDozeParameters.getPickupVibrationThreshold()) {
               if (DEBUG) Log.d(mTag, "Not resetting schedule, recent notification");
            } else {
                resetNotificationResets();
            }
        }
    }
}
