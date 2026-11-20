package dev.wolklaw.soot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * The SEND CLIPBOARD button on the Link notification. Android lets an app read the clipboard only
 * while one of its windows has focus, so this invisible screen opens, reads it, sends it and closes.
 */
public final class ClipSendActivity extends Activity {
    private boolean done;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus || done) return;
        done = true;
        String text = null;
        try {
            ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).coerceToText(this) != null)
                text = clip.getItemAt(0).coerceToText(this).toString();
        } catch (Exception ignored) { }
        String said;
        if (text == null || text.isEmpty()) said = "The clipboard is empty";
        else if (LinkService.sendClipboard(this, text)) said = "Clipboard sent to " + LinkState.pcName(this);
        else said = LinkService.connected() ? "The shared clipboard is off on the PC or here" : "Not connected to " + LinkState.pcName(this);
        Toast.makeText(this, said, Toast.LENGTH_SHORT).show();
        finish();
    }
}
