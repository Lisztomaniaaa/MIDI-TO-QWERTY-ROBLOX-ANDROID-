package com.lisztomaniaaa.papiano;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Transparent Activity that opens a file picker for .mid files,
 * then forwards the result to FloatingPanelService.
 * Finishes immediately after selection.
 */
public class MidiPickerActivity extends Activity {
    private static final int PICK_MIDI = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"audio/midi", "audio/x-midi", "application/x-midi", "*/*"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        try {
            startActivityForResult(intent, PICK_MIDI);
        } catch (Exception e) {
            // Fallback: try without mime filter
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("*/*");
            startActivityForResult(fallback, PICK_MIDI);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_MIDI && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                // Take persistent permission
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}

                // Store URI and notify service
                FloatingPanelService.setPendingMidiUri(uri);
                // Send broadcast to self to trigger load
                sendBroadcast(new Intent("intent.tuoluoyi.midi_file_picked")
                        .setPackage(getPackageName()));
            }
        }
        finish();
    }
}
