package org.jak_linux.dns66.vpn;

import android.content.Context;

class ContextConfigProvider implements AdVpnService.ConfigProvider {

    private final Context context;

    ContextConfigProvider(Context context) {
        this.context = context;
    }

    @Override
    public Configuration retrieveConfig() {
        return FileHelper.loadCurrentSettings(context);
    }
}
