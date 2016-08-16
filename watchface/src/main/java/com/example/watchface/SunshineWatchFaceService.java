package com.example.watchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;

import java.util.Calendar;
import java.util.TimeZone;

public class SunshineWatchFaceService extends CanvasWatchFaceService{

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    @Override
    public Engine onCreateEngine() {
        return new SunshineEngine();
    }

    private class SunshineEngine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        boolean mLowBitAmbient;
        boolean mBurnInProtection;

        private GoogleApiClient mGoogleApiClient;
        private Calendar mCalendar;
        private boolean mRegisteredReceiver = false;

        private float mXOffset;
        private float mXDistanceOffset;
        private float mYOffset;
        private float mLineHeight;

        private float mColonWidth;

        private String mAmString;
        private String mPmString;

        private Paint mHourPaint;
        private Paint mMinutePaint;
        private Paint mAmPmPaint;
        private Paint mColonPaint;

        private int backgroundColor;
        private static final String COLON_STRING = ":";

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mHourPaint = createTextPaint(Color.WHITE);
            mMinutePaint = createTextPaint(Color.WHITE);
            mAmPmPaint = createTextPaint(Color.WHITE);
            mColonPaint = createTextPaint(Color.WHITE);

            Resources resources = getResources();

            backgroundColor = resources.getColor(R.color.primary);
            mYOffset = resources.getDimension(R.dimen.fit_y_offset);
            mLineHeight = resources.getDimension(R.dimen.fit_line_height);
            mAmString = resources.getString(R.string.fit_am);
            mPmString = resources.getString(R.string.fit_pm);

            mCalendar = Calendar.getInstance();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mCalendar.setTimeInMillis(System.currentTimeMillis());
            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFaceService.this);

            canvas.drawColor(backgroundColor);

            // Draw the hours.
            String hourString;
            if (is24Hour) {
                hourString = String.format("%02d", mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }
            String minuteString = String.format("%02d", mCalendar.get(Calendar.MINUTE));

            float x = (bounds.width() / 2) - ((mHourPaint.measureText(hourString) + mColonWidth + mMinutePaint.measureText(minuteString)) / 2);
            canvas.drawText(hourString, x, mYOffset, mHourPaint);
            x += mHourPaint.measureText(hourString);
            canvas.drawText(COLON_STRING, x, mYOffset, mColonPaint);
            x += mColonWidth;
            canvas.drawText(minuteString, x, mYOffset, mMinutePaint);

            float y = mYOffset + mLineHeight;
            // FORMAT DATE + draw it
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound ? R.dimen.fit_x_offset_round : R.dimen.fit_x_offset);
            mXDistanceOffset =
                    resources.getDimension(
                            isRound ?
                                    R.dimen.fit_steps_or_distance_x_offset_round :
                                    R.dimen.fit_steps_or_distance_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.fit_text_size_round : R.dimen.fit_text_size);
            float amPmSize = resources.getDimension(isRound
                    ? R.dimen.fit_am_pm_size_round : R.dimen.fit_am_pm_size);

            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mAmPmPaint.setTextSize(amPmSize);
            mColonPaint.setTextSize(textSize);

            mColonWidth = mColonPaint.measureText(COLON_STRING);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible and
            // whether we're in ambient mode, so we may need to start or stop the timer
            updateTimer();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;;
                mHourPaint.setAntiAlias(antiAlias);
                mMinutePaint.setAntiAlias(antiAlias);
                mAmPmPaint.setAntiAlias(antiAlias);
                mColonPaint.setAntiAlias(antiAlias);
            }
            invalidate();

            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION,
                    false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();

            invalidate();
        }

        private void updateTimer() {
            /*mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldUpdateTimeHandlerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }*/
        }

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        private void registerReceiver() {
            if (mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mReceiver);
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {

        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        private Paint createTextPaint(int color) {
            return createTextPaint(color, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int color, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(color);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }
    }
}
