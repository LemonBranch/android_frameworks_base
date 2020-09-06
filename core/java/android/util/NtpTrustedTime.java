/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (C) 2018 The LineageOS Project
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
 * limitations under the License.
 */

package android.util;

import android.annotation.UnsupportedAppUsage;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.SntpClient;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * {@link TrustedTime} that connects with a remote NTP server as its trusted
 * time source.
 *
 * @hide
 */
public class NtpTrustedTime implements TrustedTime {
    private static final String TAG = "NtpTrustedTime";
    private static final boolean LOGD = false;

    private static NtpTrustedTime sSingleton;
    private static Context sContext;

    private final String mServer;
    private final String mNtpMode;
    private final long mTimeout;

    private ConnectivityManager mCM;

    private boolean mHasCache;
    private long mCachedNtpTime;
    private long mCachedNtpElapsedRealtime;
    private long mCachedNtpCertainty;

    private boolean mBackupmode = false;
    private static String mBackupServer = "";
    private static int mNtpRetries = 0;
    private static int mNtpRetriesMax = 0;
    private static final String BACKUP_SERVER = "persist.backup.ntpServer";

    private NtpTrustedTime(String server, long timeout, String ntpMode) {
        if (LOGD) Log.d(TAG, "creating NtpTrustedTime using " + server);
        mServer = server;
        mTimeout = timeout;
        mNtpMode = ntpMode;
    }

    @UnsupportedAppUsage
    public static synchronized NtpTrustedTime getInstance(Context context) {
        if (sSingleton == null) {
            final Resources res = context.getResources();
            final ContentResolver resolver = context.getContentResolver();

            final String defaultNtpMode = res.getString(
                    com.android.internal.R.string.config_ntpMode);
            final String ntpMode =
                    Settings.Global.getString(resolver, Settings.Global.NTP_MODE) != null ?
                    Settings.Global.getString(resolver, Settings.Global.NTP_MODE) :
                    defaultNtpMode;

            final long defaultTimeout = res.getInteger(
                    com.android.internal.R.integer.config_ntpTimeout);

            final long timeout = Settings.Global.getLong(
                    resolver, Settings.Global.NTP_TIMEOUT, defaultTimeout);

            String defaultServer;
            String secureServer;

            switch(ntpMode) {
                case "https":
                    defaultServer = res.getString(
                            com.android.internal.R.string.config_httpsTimeServer);
                    secureServer = Settings.Global.getString(
                            resolver, Settings.Global.HTTPS_TIME_SERVER);
                    break;
                default:
                    defaultServer = res.getString(
                            com.android.internal.R.string.config_ntpServer);
                    secureServer = Settings.Global.getString(
                            resolver, Settings.Global.NTP_SERVER);
                    break;
            }

            final String server = secureServer != null ? secureServer : defaultServer;
            sSingleton = new NtpTrustedTime(server, timeout, ntpMode);
            sContext = context;

            final String sserver_prop = Settings.Global.getString(
                    resolver, Settings.Global.NTP_SERVER_2);

            final String secondServer_prop = ((null != sserver_prop)
                                               && (0 < sserver_prop.length()))
                                               ? sserver_prop : BACKUP_SERVER;

            final String backupServer = SystemProperties.get(secondServer_prop);

            if ((null != backupServer) && (0 < backupServer.length())) {
                int retryMax = res.getInteger(com.android.internal.R.integer.config_ntpRetry);
                if (0 < retryMax) {
                    sSingleton.mNtpRetriesMax = retryMax;
                    sSingleton.mBackupServer = (backupServer.trim()).replace("\"", "");
                }
            }
        }

        return sSingleton;
    }

    @Override
    @UnsupportedAppUsage
    public boolean forceRefresh() {
        return hasCache() ? forceSync() : false;
    }

    @Override
    public boolean forceSync() {
        // We can't do this at initialization time: ConnectivityService might not be running yet.
        synchronized (this) {
            if (mCM == null) {
                mCM = sContext.getSystemService(ConnectivityManager.class);
            }
        }

        final Network network = mCM == null ? null : mCM.getActiveNetwork();
        return forceRefresh(network);
    }

    public boolean forceRefresh(Network network) {
        final String realServer = TextUtils.isEmpty(mServer) ? sContext.getResources().getString(
                com.android.internal.R.string.config_ntpServer) : mServer;

        // We can't do this at initialization time: ConnectivityService might not be running yet.
        synchronized (this) {
            if (mCM == null) {
                mCM = sContext.getSystemService(ConnectivityManager.class);
            }
        }

        final NetworkInfo ni = mCM == null ? null : mCM.getNetworkInfo(network);
        if (ni == null || !ni.isConnected()) {
            if (LOGD) Log.d(TAG, "forceRefresh: no connectivity");
            return false;
        }

        if (LOGD) Log.d(TAG, "forceRefresh() from cache miss");

        final SntpClient client = new SntpClient();
        client.setNtpMode(mNtpMode);
        String targetServer = realServer;
        if (getBackupmode()) {
            setBackupmode(false);
            targetServer = mBackupServer;
        }
        if (LOGD) Log.d(TAG, "Ntp Server to access at:" + targetServer);
        if (client.requestTime(targetServer, (int) mTimeout, network)) {
            mHasCache = true;
            mCachedNtpTime = client.getNtpTime();
            mCachedNtpElapsedRealtime = client.getNtpTimeReference();
            mCachedNtpCertainty = client.getRoundTripTime() / 2;
            return true;
        } else {
            countInBackupmode();
            return false;
        }
    }

    @Override
    @UnsupportedAppUsage
    public boolean hasCache() {
        return mHasCache;
    }

    @Override
    public long getCacheAge() {
        if (mHasCache) {
            return SystemClock.elapsedRealtime() - mCachedNtpElapsedRealtime;
        } else {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public long getCacheCertainty() {
        if (mHasCache) {
            return mCachedNtpCertainty;
        } else {
            return Long.MAX_VALUE;
        }
    }

    @Override
    @UnsupportedAppUsage
    public long currentTimeMillis() {
        if (!mHasCache) {
            throw new IllegalStateException("Missing authoritative time source");
        }
        if (LOGD) Log.d(TAG, "currentTimeMillis() cache hit");

        // current time is age after the last ntp cache; callers who
        // want fresh values will hit makeAuthoritative() first.
        return mCachedNtpTime + getCacheAge();
    }

    @UnsupportedAppUsage
    public long getCachedNtpTime() {
        if (LOGD) Log.d(TAG, "getCachedNtpTime() cache hit");
        return mCachedNtpTime;
    }

    @UnsupportedAppUsage
    public long getCachedNtpTimeReference() {
        return mCachedNtpElapsedRealtime;
    }

    public void setBackupmode(boolean mode) {
        if (isBackupSupported()) {
            mBackupmode = mode;
        }
        if (LOGD) Log.d(TAG, "setBackupmode() set the backup mode to be:" + mBackupmode);
    }

    private boolean getBackupmode() {
        return mBackupmode;
    }

    private boolean isBackupSupported() {
        return ((0 < mNtpRetriesMax) &&
                (null != mBackupServer) &&
                (0 != mBackupServer.length()));
    }

    private void countInBackupmode() {
        if (isBackupSupported()) {
            mNtpRetries++;
            if (mNtpRetries >= mNtpRetriesMax) {
                mNtpRetries = 0;
                setBackupmode(true);
            }
        }
        if (LOGD) Log.d(TAG, "countInBackupmode() func");
    }
}
