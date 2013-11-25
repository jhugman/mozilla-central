package org.mozilla.gecko.webapp;

import org.mozilla.gecko.WebAppAllocator;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class WebAppDispatcher extends Activity {
    private static final String LOGTAG = "GeckoWebAppDispatcher";

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        WebAppAllocator allocator = WebAppAllocator.getInstance(getApplicationContext());

        if (bundle == null) {
            bundle = getIntent().getExtras();
        }

        String packageName = bundle.getString("packageName");

        int index = allocator.getIndexForApp(packageName);
        boolean isInstalled = index >= 0;
        if (!isInstalled) {
            index = allocator.allocatePackage(packageName, packageName);
        }

        // Copy the intent, without interfering with it.
        Intent intent = new Intent(getIntent());

        // Only change it's destination.
        intent.setClassName(getApplicationContext(), getPackageName() + ".WebApps$WebApp" + index);

        // If and only if we haven't seen this before.
        intent.putExtra("isInstalled", isInstalled);

        startActivity(intent);
    }


}
