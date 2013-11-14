package org.mozilla.gecko.tests;

import org.mozilla.gecko.*;

import com.jayway.android.robotium.solo.Condition;

import org.json.JSONObject;

/**
 *
 */
public class testWebapp extends BaseTest {
    private static final int MAX_WAIT_ENABLED_TEXT_MS = 10000;

    @Override
    protected int getTestType() {
        return TEST_MOCHITEST;
    }

    public void testWebapp() {
        String url = getAbsoluteUrl("/robocop/robocop_webapp_installer.html");

        blockForGeckoReady();
        setPref();
        inputAndLoadUrl(url);
        verifyUrl(url);
        waitAwhile();
    }

    protected final void waitAwhile() {
        boolean success = waitForCondition(new Condition() {
            @Override
            public boolean isSatisfied() {
                return false;
            }
        // }, MAX_WAIT_ENABLED_TEXT_MS);
        }, 30000);
    }

    protected final void setPref() {
        JSONObject jsonPref = new JSONObject();
        try {
            jsonPref.put("name", "dom.mozApps.apkGeneratorEndpoint");
            jsonPref.put("type", "string");
            jsonPref.put("value", getAbsoluteUrl("/robocop/robocop_webapp.apk"));
            mActions.sendGeckoEvent("Preferences:Set", jsonPref.toString());

            // Wait for confirmation of the pref change before proceeding with the test.
            final String[] prefNames = { "dom.mozApps.apkGeneratorEndpoint" };
            final int ourRequestId = 0x7357;
            Actions.RepeatedEventExpecter eventExpecter = mActions.expectGeckoEvent("Preferences:Data");
            mActions.sendPreferencesGetEvent(ourRequestId, prefNames);

            JSONObject data = null;
            int requestId = -1;

            // Wait until we get the correct "Preferences:Data" event
            while (requestId != ourRequestId) {
                data = new JSONObject(eventExpecter.blockForEventData());
                requestId = data.getInt("requestId");
            }
            eventExpecter.unregisterListener();
        } catch (Exception ex) {
            mAsserter.ok(false, "exception in testWebapp", ex.toString());
        }
    }

}
