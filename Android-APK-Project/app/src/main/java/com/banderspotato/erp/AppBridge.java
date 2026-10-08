package com.banderspotato.erp;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * What the web page can ask the phone to do (the page calls window.AndroidBridge.*):
 *  - saveFile: keep a PDF / CSV / photo in the Downloads folder
 *  - shareFile: open the phone's share sheet with the file and a message (choose WhatsApp and the group)
 */
public class AppBridge {

    private final Activity activity;

    public AppBridge(Activity activity) {
        this.activity = activity;
    }

    private void toast(final String message) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String safeName(String name) {
        String n = name == null ? "file" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "file" : n;
    }

    @JavascriptInterface
    public boolean isApp() {
        return true;
    }

    /** Saves the file in Downloads/BandersPotatoERP. Returns "ok" or "error: ...". */
    @JavascriptInterface
    public String saveFile(String name, String mime, String base64) {
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            String fileName = safeName(name);
            String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver resolver = activity.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, type);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BandersPotatoERP");
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("cannot create the file");
                OutputStream out = resolver.openOutputStream(uri);
                if (out == null) throw new IllegalStateException("cannot open the file");
                try {
                    out.write(data);
                } finally {
                    out.close();
                }
                toast("Saved in Downloads/BandersPotatoERP: " + fileName);
            } else {
                File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = activity.getFilesDir();
                dir.mkdirs();
                File file = new File(dir, fileName);
                FileOutputStream out = new FileOutputStream(file);
                try {
                    out.write(data);
                } finally {
                    out.close();
                }
                toast("Saved: " + file.getAbsolutePath());
            }
            return "ok";
        } catch (Exception e) {
            toast("Could not save the file: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    /** Opens the share sheet with the file attached and the text ready (WhatsApp is in the list). */
    @JavascriptInterface
    public String shareFile(String name, String mime, String base64, String text) {
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            File dir = new File(activity.getCacheDir(), "shared");
            if (!dir.exists()) dir.mkdirs();
            final File file = new File(dir, safeName(name));
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            final Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
            final String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
            final String message = text == null ? "" : text;
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(type);
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_TEXT, message);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(send, "Send"));
                }
            });
            return "ok";
        } catch (Exception e) {
            toast("Could not share the file: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }
}
