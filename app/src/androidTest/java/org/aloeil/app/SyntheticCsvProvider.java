package org.aloeil.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test APK only: writable synthetic destination for CSV cancellation coverage. */
public final class SyntheticCsvProvider extends ContentProvider {
    private static volatile CountDownLatch writeGate;
    private static final AtomicBoolean waitingForRelease = new AtomicBoolean(false);
    private static volatile CountDownLatch readGate;
    private static final AtomicBoolean waitingForReadRelease = new AtomicBoolean(false);
    private static final AtomicInteger deniedCalls = new AtomicInteger(0);

    private void enforceFixtureCaller() {
        int callerUid = Binder.getCallingUid();
        int ownerUid = Process.myUid();
        if (callerUid == ownerUid) return;
        Context context = Objects.requireNonNull(getContext());
        PackageManager packages = context.getPackageManager();
        try {
            int targetUid = packages.getApplicationInfo("org.aloeil.app", 0).uid;
            if (callerUid == targetUid
                    && packages.checkSignatures(ownerUid, targetUid)
                            == PackageManager.SIGNATURE_MATCH) return;
        } catch (PackageManager.NameNotFoundException ignored) {
            // A missing target never broadens access to the synthetic fixture.
        }
        deniedCalls.incrementAndGet();
        throw new SecurityException("Synthetic fixture caller is not authorized");
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceFixtureCaller();
        Bundle result = new Bundle();
        if ("hold".equals(method)) {
            writeGate = new CountDownLatch(1);
            waitingForRelease.set(false);
        } else if ("release".equals(method)) {
            CountDownLatch gate = writeGate;
            writeGate = null;
            if (gate != null) gate.countDown();
        } else if ("waiting".equals(method)) {
            result.putBoolean("waiting", waitingForRelease.get());
        } else if ("holdRead".equals(method)) {
            readGate = new CountDownLatch(1);
            waitingForReadRelease.set(false);
        } else if ("releaseRead".equals(method)) {
            CountDownLatch gate = readGate;
            readGate = null;
            if (gate != null) gate.countDown();
        } else if ("waitingRead".equals(method)) {
            result.putBoolean("waiting", waitingForReadRelease.get());
        } else if ("deniedCalls".equals(method)) {
            // Authorized tests can observe rejection without relying on shell stderr capture.
            result.putInt("count", deniedCalls.get());
        }
        return result;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "export.archive".equals(uri.getLastPathSegment())
                ? "application/octet-stream" : "text/csv";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceFixtureCaller();
        File file = new File(Objects.requireNonNull(getContext()).getCacheDir(),
                "export.archive".equals(uri.getLastPathSegment())
                        ? "synthetic-archive-save.bin" : "synthetic-csv-save.csv");
        if ("r".equals(mode)) {
            CountDownLatch gate = readGate;
            if (gate != null) {
                waitingForReadRelease.set(true);
                try {
                    if (!gate.await(45, TimeUnit.SECONDS)) {
                        throw new FileNotFoundException("Synthetic archive read was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new FileNotFoundException("Synthetic archive read interrupted");
                } finally {
                    waitingForReadRelease.set(false);
                }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        CountDownLatch gate = writeGate;
        if (gate != null) {
            waitingForRelease.set(true);
            try {
                if (!gate.await(45, TimeUnit.SECONDS)) {
                    throw new FileNotFoundException("Synthetic CSV write was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new FileNotFoundException("Synthetic CSV write interrupted");
            } finally {
                waitingForRelease.set(false);
            }
        }
        return ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                        | ParcelFileDescriptor.MODE_WRITE_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
