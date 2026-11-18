package com.mosami11.shifti;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends Activity {
    private static final int CALENDAR_PERMISSION_REQUEST = 41;
    private WebView webView;
    private boolean pageReady = false;
    private boolean syncAfterPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDefaultTextEncodingName("UTF-8");
        webView.addJavascriptInterface(new CalendarBridge(), "AndroidCalendar");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (hasCalendarPermission()) syncCalendarEvents();
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    private boolean hasCalendarPermission() {
        return checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestOrSyncCalendar() {
        if (hasCalendarPermission()) {
            syncCalendarEvents();
        } else {
            syncAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, CALENDAR_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CALENDAR_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (syncAfterPermission) syncCalendarEvents();
            } else {
                sendCalendarError("محتاج تسمح للتطبيق بقراءة التقويم علشان يقدر يجيب أيام Work day.");
            }
            syncAfterPermission = false;
        }
    }

    private void sendCalendarError(String message) {
        if (webView != null) {
            String safe = JSONObject.quote(message);
            webView.post(() -> webView.evaluateJavascript("window.onCalendarSyncError && window.onCalendarSyncError(" + safe + ")", null));
        }
    }

    private void syncCalendarEvents() {
        if (!pageReady || !hasCalendarPermission()) return;
        try {
            Calendar from = Calendar.getInstance();
            from.add(Calendar.YEAR, -2);
            from.set(Calendar.MONTH, Calendar.JANUARY);
            from.set(Calendar.DAY_OF_MONTH, 1);
            from.set(Calendar.HOUR_OF_DAY, 0);
            from.set(Calendar.MINUTE, 0);
            from.set(Calendar.SECOND, 0);
            from.set(Calendar.MILLISECOND, 0);
            Calendar to = Calendar.getInstance();
            to.add(Calendar.YEAR, 3);
            to.set(Calendar.MONTH, Calendar.DECEMBER);
            to.set(Calendar.DAY_OF_MONTH, 31);
            to.set(Calendar.HOUR_OF_DAY, 23);
            to.set(Calendar.MINUTE, 59);
            to.set(Calendar.SECOND, 59);
            to.set(Calendar.MILLISECOND, 999);

            Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
            builder.appendPath(Long.toString(from.getTimeInMillis()));
            builder.appendPath(Long.toString(to.getTimeInMillis()));
            String[] projection = {
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY
            };
            JSONArray days = new JSONArray();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            Cursor cursor = getContentResolver().query(builder.build(), projection, null, null,
                    CalendarContract.Instances.BEGIN + " ASC");
            if (cursor != null) {
                try {
                    int titleCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE);
                    int beginCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN);
                    int allDayCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY);
                    SimpleDateFormat localDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    SimpleDateFormat utcDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    utcDate.setTimeZone(TimeZone.getTimeZone("UTC"));
                    while (cursor.moveToNext()) {
                        String title = cursor.getString(titleCol);
                        if (title == null || !title.trim().equalsIgnoreCase("work day")) continue;
                        long begin = cursor.getLong(beginCol);
                        boolean allDay = cursor.getInt(allDayCol) == 1;
                        String date = (allDay ? utcDate : localDate).format(new Date(begin));
                        if (seen.add(date)) days.put(date);
                    }
                } finally {
                    cursor.close();
                }
            }
            String payload = days.toString();
            webView.post(() -> webView.evaluateJavascript("window.onCalendarSync && window.onCalendarSync(" + JSONObject.quote(payload) + ")", null));
        } catch (Exception e) {
            sendCalendarError("حصلت مشكلة أثناء قراءة التقويم. اتأكد إن إذن التقويم مفعّل.");
        }
    }

    private class CalendarBridge {
        @JavascriptInterface
        public void sync() {
            runOnUiThread(() -> requestOrSyncCalendar());
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
