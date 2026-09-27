package org.aloeil.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

/** Test APK only: a cancelable picker for the minimal Android emulator. */
public final class SyntheticDocumentPickerActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean csv = "text/csv".equals(getIntent().getType());
        boolean archive = "application/octet-stream".equals(getIntent().getType());
        if (Intent.ACTION_CREATE_DOCUMENT.equals(getIntent().getAction())
                && (csv || archive)) {
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
