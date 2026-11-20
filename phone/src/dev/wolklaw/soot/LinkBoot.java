package dev.wolklaw.soot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the background link after the phone starts or Soot is updated, when the owner left it on. */
public final class LinkBoot extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (LinkState.paired(c) && LinkState.background(c)) LinkService.start(c);
    }
}
