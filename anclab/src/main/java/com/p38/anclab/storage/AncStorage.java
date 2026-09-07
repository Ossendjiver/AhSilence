package com.p38.anclab.storage;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Canonical user-visible ANC Lab storage.
 *
 * Android scoped storage makes raw pathname access to Documents/ANC unreliable on modern Android.
 * The app therefore asks the user to grant a persistent SAF tree permission to Documents/ANC once.
 * All profiles, calibrations, logs and WAVs are then read/written through that tree.
 */
public final class AncStorage {
    private static final String PREFS = "anc_storage";
    private static final String KEY_TREE = "tree_uri";
    public static final String DISPLAY_PATH = "Internal storage/Documents/ANC";

    private final Context context;
    private final ContentResolver resolver;
    private Uri treeUri;

    public AncStorage(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        String saved = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null);
        if (!TextUtils.isEmpty(saved)) treeUri = Uri.parse(saved);
    }

    public boolean isConnected() {
        if (treeUri == null) return false;
        for (android.content.UriPermission p : resolver.getPersistedUriPermissions()) {
            if (treeUri.equals(p.getUri()) && p.isReadPermission() && p.isWritePermission()) return true;
        }
        return false;
    }

    public Uri getTreeUri() { return treeUri; }

    public Intent createTreePickerIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        i.putExtra("android.provider.extra.INITIAL_URI",
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments%2FANC"));
        return i;
    }

    public void persistTree(Uri uri, int flags) {
        int takeFlags = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        resolver.takePersistableUriPermission(uri, takeFlags);
        treeUri = uri;
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(KEY_TREE, uri.toString()).apply();
        ensureStandardFolders();
        writeText("README-storage.txt",
                "ANC Lab user data folder\n" +
                "This directory is authoritative for profiles, calibrations, logs and exported WAV files.\n" +
                "The app may use temporary internal files while recording, then mirrors them here automatically.\n");
    }

    public void ensureStandardFolders() {
        if (!isConnected()) return;
        ensureDirectory("profiles");
        ensureDirectory("profiles/headphones");
        ensureDirectory("profiles/p38");
        ensureDirectory("calibration");
        ensureDirectory("logs");
        ensureDirectory("wav");
        ensureDirectory("recipes");
        ensureDirectory("exports");
    }

    public boolean exists(String relativePath) {
        return resolve(relativePath, false, null) != null;
    }

    public String readText(String relativePath) {
        Uri u = resolve(relativePath, false, null);
        if (u == null) return null;
        try (InputStream in = resolver.openInputStream(u)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized boolean writeText(String relativePath, String text) {
        return writeBytes(relativePath, "text/plain", text.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized boolean writeJson(String relativePath, String text) {
        return writeBytes(relativePath, "application/json", text.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized boolean appendText(String relativePath, String text) {
        if (!isConnected()) return false;
        Uri u = resolve(relativePath, true, mimeFor(relativePath));
        if (u == null) return false;
        try (OutputStream out = resolver.openOutputStream(u, "wa")) {
            if (out == null) return false;
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (Exception appendFailed) {
            String old = readText(relativePath);
            return writeText(relativePath, (old == null ? "" : old) + text);
        }
    }

    public synchronized boolean writeBytes(String relativePath, String mime, byte[] data) {
        if (!isConnected()) return false;
        Uri u = resolve(relativePath, true, mime);
        if (u == null) return false;
        try (OutputStream out = resolver.openOutputStream(u, "wt")) {
            if (out == null) return false;
            out.write(data);
            out.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean copyFileToTree(File src, String relativePath, String mime) {
        if (!isConnected() || src == null || !src.exists()) return false;
        Uri u = resolve(relativePath, true, mime);
        if (u == null) return false;
        try (InputStream in = new FileInputStream(src); OutputStream out = resolver.openOutputStream(u, "wt")) {
            if (out == null) return false;
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public File copyTreeFileToCache(String relativePath) {
        Uri u = resolve(relativePath, false, null);
        if (u == null) return null;
        File dst = new File(context.getCacheDir(), "anc-import-" + System.nanoTime());
        try (InputStream in = resolver.openInputStream(u); OutputStream out = new FileOutputStream(dst)) {
            if (in == null) return null;
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return dst;
        } catch (IOException e) {
            return null;
        }
    }

    public String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    private String mimeFor(String path) {
        String p = path.toLowerCase(Locale.US);
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".jsonl") || p.endsWith(".csv") || p.endsWith(".log") || p.endsWith(".txt")) return "text/plain";
        if (p.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    private Uri ensureDirectory(String path) {
        return resolve(path, true, DocumentsContract.Document.MIME_TYPE_DIR);
    }

    private Uri resolve(String relativePath, boolean create, String mime) {
        if (!isConnected() || TextUtils.isEmpty(relativePath)) return null;
        String[] parts = relativePath.replace('\\', '/').split("/");
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri current = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i];
            if (TextUtils.isEmpty(name)) continue;
            boolean last = i == parts.length - 1;
            Uri child = findChild(current, name);
            if (child == null && create) {
                String type = last ? (mime == null ? mimeFor(name) : mime) : DocumentsContract.Document.MIME_TYPE_DIR;
                try { child = DocumentsContract.createDocument(resolver, current, type, name); }
                catch (Exception ignored) { child = null; }
            }
            if (child == null) return null;
            current = child;
        }
        return current;
    }

    private Uri findChild(Uri parent, String displayName) {
        try {
            String parentId = DocumentsContract.getDocumentId(parent);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
            String[] projection = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME };
            try (Cursor c = resolver.query(children, projection, null, null, null)) {
                if (c == null) return null;
                while (c.moveToNext()) {
                    if (displayName.equals(c.getString(1))) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
