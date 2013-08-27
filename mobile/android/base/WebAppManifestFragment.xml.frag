        <activity android:name=".WebApps$WebApp@APPNUM@"
                  android:label="@string/webapp_generic_name"
                  android:configChanges="keyboard|keyboardHidden|mcc|mnc|orientation|screenSize"
                  android:windowSoftInputMode="stateUnspecified|adjustResize"
                  android:launchMode="singleTask"
                  android:process=":@ANDROID_PACKAGE_NAME@.WebApp@APPNUM@"
                  android:exported="true"
                  android:theme="@style/Gecko.App"
                  />


