package org.aloeil.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

/** Test APK only: a cancelable picker for the minimal Android emulator. */
public final class SyntheticDocumentPickerActivity extends Activity {
    private static boolean requestsMime(Intent intent, String mime) {
        if (mime.equals(intent.getType())) return true;
        String[] requested = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        if (requested == null) return false;
        for (String candidate : requested) {
            if (mime.equals(candidate)) return true;
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean csv = requestsMime(getIntent(), "text/csv");
        boolean archive = requestsMime(getIntent(), "application/octet-stream");
        boolean create = Intent.ACTION_CREATE_DOCUMENT.equals(getIntent().getAction())
                && (csv || archive);
        boolean openArchive = Intent.ACTION_OPEN_DOCUMENT.equals(getIntent().getAction()) && archive;
        if (create || openArchive) {
            Button choose = new Button(this);
            choose.setText(csv ? "Select synthetic CSV" : "Select synthetic archive");
            choose.setOnClickListener(view -> {
                Intent result = new Intent();
                result.setData(Uri.parse(csv
                        ? "content://org.aloeil.app.test.syntheticcsv/export.csv"
                        : "content://org.aloeil.app.test.syntheticcsv/export.archive"));
                result.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                setResult(RESULT_OK, result);
                finish();
            });
            setContentView(choose);
            return;
        }
        TextView label = new TextView(this);
        label.setText("Synthetic document picker");
        setContentView(label);
    }
}
