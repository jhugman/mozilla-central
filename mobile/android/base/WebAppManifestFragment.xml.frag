        <activity android:name=".WebApps$WebApp@APPNUM@"
                  android:label="@string/webapp_generic_name"
                  android:configChanges="keyboard|keyboardHidden|mcc|mnc|orientation|screenSize"
                  android:windowSoftInputMode="stateUnspecified|adjustResize"
                  android:launchMode="singleTask"
                  android:process=":@ANDROID_PACKAGE_NAME@.WebApp@APPNUM@"
                  android:exported="true"
                  android:theme="@style/Gecko.App">
            <intent-filter>
                <action android:name="org.mozilla.gecko.WEBAPP@APPNUM@" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/webapp"/>
            </intent-filter>
            <intent-filter>
                <action android:name="org.mozilla.gecko.ACTION_ALERT_CALLBACK" />
            </intent-filter>
        </activity>

