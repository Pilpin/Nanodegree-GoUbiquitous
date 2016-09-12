package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.data.BitmapTeleporter;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;

public class SunshineWatchFaceService extends CanvasWatchFaceService{

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final long INTERACTIVE_UPDATE_RATE_MS = 60000;

    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new SunshineEngine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.SunshineEngine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.SunshineEngine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.SunshineEngine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class SunshineEngine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        private final String LOG_TAG = "SunshineEngine";
        boolean mLowBitAmbient;
        boolean mBurnInProtection;

        private GoogleApiClient mGoogleApiClient;
        public static final String PATH = "/weather";
        public static final String WEATHER_ID = "weather_id";
        public static final String MAX_TMP = "max_tmp";
        public static final String MIN_TMP = "min_tmp";

        private String highTmp = "0";
        private String lowTmp = "0";
        private Bitmap mWeatherIcon;
        private Bitmap mWeatherIconBW;

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
        private Paint mDatePaint;
        private Paint mLinePaint;
        private Paint mWeatherIconPaint;
        private Paint mTempMaxPaint;
        private Paint mTempMinPaint;

        private int backgroundColor;
        private static final String COLON_STRING = ":";

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mHourPaint = createTextPaint(Color.WHITE, BOLD_TYPEFACE);
            mMinutePaint = createTextPaint(Color.WHITE);
            mAmPmPaint = createTextPaint(Color.WHITE);
            mColonPaint = createTextPaint(Color.WHITE);
            mDatePaint = createTextPaint(getColor(R.color.date));
            mLinePaint = new Paint();
            mLinePaint.setColor(getColor(R.color.date));
            mLinePaint.setAntiAlias(true);
            mWeatherIcon = BitmapFactory.decodeResource(getResources(), getResources().getIdentifier("ic_clear", "drawable", getPackageName()));
            mWeatherIconBW = BitmapFactory.decodeResource(getResources(), getResources().getIdentifier("ic_clear_bw", "drawable", getPackageName()));
            mWeatherIconPaint = new Paint();
            mTempMaxPaint = createTextPaint(Color.WHITE, BOLD_TYPEFACE);
            mTempMinPaint = createTextPaint(getColor(R.color.date));

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

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mCalendar.setTimeInMillis(System.currentTimeMillis());
            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFaceService.this);

            if(isInAmbientMode()){
                canvas.drawColor(Color.BLACK);
            }else {
                canvas.drawColor(backgroundColor);
            }

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
            String tempsMaxString = highTmp + "°";
            String tempsMinString = lowTmp + "°";

            String dateString = DateUtils.formatDateTime(getApplicationContext(), mCalendar.getTimeInMillis(), DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_YEAR);
            dateString = dateString.toUpperCase();

            float x = (bounds.width() / 2) - ((mHourPaint.measureText(hourString) + mColonWidth + mMinutePaint.measureText(minuteString)) / 2);
            canvas.drawText(hourString, x, mYOffset, mHourPaint);
            x += mHourPaint.measureText(hourString);
            canvas.drawText(COLON_STRING, x, mYOffset, mColonPaint);
            x += mColonWidth;
            canvas.drawText(minuteString, x, mYOffset, mMinutePaint);

            float y = mYOffset + mLineHeight;
            x = (bounds.width() / 2) - (mDatePaint.measureText(dateString) / 2);
            canvas.drawText(dateString, x, y, mDatePaint);

            if(!isInAmbientMode() || (!mLowBitAmbient && !mBurnInProtection)) {
                y += mLineHeight;
                canvas.drawLine((bounds.width() / 2) - getResources().getDimension(R.dimen.middleline_length), y, (bounds.width() / 2) + getResources().getDimension(R.dimen.middleline_length), y, mLinePaint);

                y += mLineHeight / 4;
                if(isInAmbientMode()) {
                    x = (bounds.width() - (mWeatherIconBW.getWidth() + 15 + mTempMaxPaint.measureText(tempsMaxString) + 15 + mTempMinPaint.measureText(tempsMinString))) / 2;
                    canvas.drawBitmap(mWeatherIconBW, x, y + 5, mWeatherIconPaint);
                }else {
                    x = (bounds.width() - (mWeatherIcon.getWidth() + 15 + mTempMaxPaint.measureText(tempsMaxString) + 15 + mTempMinPaint.measureText(tempsMinString))) / 2;
                    canvas.drawBitmap(mWeatherIcon, x, y + 5, mWeatherIconPaint);
                }

                y += mLineHeight + mLineHeight / 4;
                x += mWeatherIcon.getWidth() + 15;
                canvas.drawText(tempsMaxString, x, y, mTempMaxPaint);

                x += mTempMaxPaint.measureText(tempsMaxString) + 15;
                canvas.drawText(tempsMinString, x, y, mTempMinPaint);
            }

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
            float timeSize = resources.getDimension(isRound ? R.dimen.fit_time_size_round : R.dimen.fit_time_size);
            float dateSize = resources.getDimension(isRound ? R.dimen.fit_date_size_round : R.dimen.fit_date_size);
            float amPmSize = resources.getDimension(isRound ? R.dimen.fit_am_pm_size_round : R.dimen.fit_am_pm_size);
            float tempSize = resources.getDimension(isRound ? R.dimen.fit_temp_size_round : R.dimen.fit_temp_size);

            mHourPaint.setTextSize(timeSize);
            mColonPaint.setTextSize(timeSize);
            mMinutePaint.setTextSize(timeSize);
            mAmPmPaint.setTextSize(amPmSize);

            mDatePaint.setTextSize(dateSize);
            mTempMaxPaint.setTextSize(tempSize);
            mTempMinPaint.setTextSize(tempSize);

            mColonWidth = mColonPaint.measureText(COLON_STRING);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();

                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }

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
                mDatePaint.setAntiAlias(antiAlias);
                mTempMaxPaint.setAntiAlias(antiAlias);
                mTempMinPaint.setAntiAlias(antiAlias);
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
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
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

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.e(LOG_TAG, "data changed");
            for (DataEvent event : dataEventBuffer) {
                DataItem item = event.getDataItem();
                if (item.getUri().getPath().equals(PATH)) {
                    Log.e(LOG_TAG, "Data Changed for " + PATH);
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    highTmp = Integer.toString(dataMap.getInt(MAX_TMP));
                    lowTmp = Integer.toString(dataMap.getInt(MIN_TMP));
                    long weatherId = dataMap.getLong(WEATHER_ID);
                    int resID = getResources().getIdentifier("ic_" + getArtUrlForWeatherCondition(weatherId) , "drawable", getPackageName());
                    int resIDBW = getResources().getIdentifier("ic_" + getArtUrlForWeatherCondition(weatherId) + "_bw" , "drawable", getPackageName());

                    mWeatherIcon = BitmapFactory.decodeResource(getResources(), resID);
                    mWeatherIconBW = BitmapFactory.decodeResource(getResources(), resIDBW);
                }
            }
            invalidate();
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(LOG_TAG, "Connected: " + bundle);
            Wearable.DataApi.addListener(mGoogleApiClient, SunshineEngine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "Connection Suspended: " + i);
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(LOG_TAG, "Connection Failed: " + connectionResult);
        }

        public String getArtUrlForWeatherCondition(Long weatherId) {

            if (weatherId >= 200 && weatherId <= 232) {
                return "storm";
            } else if (weatherId >= 300 && weatherId <= 321) {
                return "light_rain";
            } else if (weatherId >= 500 && weatherId <= 504) {
                return "rain";
            } else if (weatherId == 511) {
                return "snow";
            } else if (weatherId >= 520 && weatherId <= 531) {
                return "rain";
            } else if (weatherId >= 600 && weatherId <= 622) {
                return "snow";
            } else if (weatherId >= 701 && weatherId <= 761) {
                return "fog";
            } else if (weatherId == 761 || weatherId == 781) {
                return "storm";
            } else if (weatherId == 800) {
                return "clear";
            } else if (weatherId == 801) {
                return "light_clouds";
            } else if (weatherId >= 802 && weatherId <= 804) {
                return "cloudy";
            }
            return null;
        }
    }
}
