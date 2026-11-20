package dev.wolklaw.soot;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Send to PC" in Android's share sheet: a link opens in Soot on the PC, other text goes to the PC's
 * clipboard (when the shared clipboard is on), files go to the PC's Downloads.
 */
public final class ShareActivity extends Activity {
    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Toast.makeText(this, share(getIntent()), Toast.LENGTH_SHORT).show();
        finish();
    }

    @SuppressWarnings("deprecation")
    private String share(Intent in) {
        String pc = LinkState.pcName(this);
        if (!LinkState.paired(this)) return "Pair Soot with your PC first: Soot, MENU, LINK TO PC";
        ArrayList<Uri> files = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            ArrayList<Uri> got = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (got != null) files.addAll(got);
        } else {
            Uri one = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) files.add(one);
        }
        if (!files.isEmpty()) {
            ClipData clip = ClipData.newRawUri("files", files.get(0));
            for (int i = 1; i < files.size(); i++) clip.addItem(new ClipData.Item(files.get(i)));
            LinkService.sendFiles(this, clip);
            return files.size() == 1 ? "Sending the file to " + pc : "Sending " + files.size() + " files to " + pc;
        }
        String text = in.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) return "Nothing to send";
        text = text.trim();
        Matcher m = URL.matcher(text);
        if (m.find() && (m.group().length() == text.length() || !LinkService.can(this, "clipboard"))) {
            if (LinkService.sendUrl(this, m.group())) return "Opening on " + pc;
            return LinkService.connected() ? "Links are switched off on the PC or here" : "Not connected to " + pc;
        }
        if (LinkService.sendClipboard(this, text)) return "Sent to the clipboard on " + pc;
        return LinkService.connected() ? "Turn on the shared clipboard to send text" : "Not connected to " + pc;
    }
}
