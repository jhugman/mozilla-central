package org.mozilla.gecko.webapp;

import org.mozilla.gecko.WebAppAllocator;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;

public class WebAppDispatcher extends Activity {
    private static final String LOGTAG = "GeckoWebAppDispatcher";

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        WebAppAllocator allocator = WebAppAllocator.getInstance(getApplicationContext());

        if (bundle == null) {
            bundle = getIntent().getExtras();
        }

        String app = bundle.getString("packageName");
        String uri = bundle.getString("iconUri");
        Log.i(LOGTAG, "Icon drawable with " + uri);


        int index = allocator.getIndexForApp(app);
        boolean isInstalled = index < 0;
        if (isInstalled) {
            index = allocator.findAndAllocateIndex(app, app, (Bitmap) null);
        }

        Intent intent = new Intent(getIntent());
        intent.setClassName(getApplicationContext(), getPackageName() + ".WebApps$WebApp" + index);

        intent.putExtra("isInstalled", isInstalled);

        startActivity(intent);
    }


}
