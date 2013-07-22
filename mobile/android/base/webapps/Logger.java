package org.mozilla.gecko.webapps;

/**
 *
 * Description:
 *   Simple logger that displays: class name, method name and line number
 *
 *
 * Usage:
 *
 *   Use this class directly or customize it by extending it.
 *
 *      Logger.i("");
 *      Logger.i("called");
 *      Logger.i("called","tag");
 *
 *      L.i();
 *      L.i("called");
 *      L.i("called","tag");
 *
 * Sub-classing example:
 *
 *    // C.DEBUG = boolean true/false (project specific constant class)
 *
 *    public class L extends HLLog {
 *
 *           public static String DEFAULT_TAG = "MH";
 *
 *           public static void i() {
 *                if(C.DEBUG) Log.i(DEFAULT_TAG, "");
 *           }
 *
 *           public static void i(String message) {
 *                  if(C.DEBUG) Log.i(DEFAULT_TAG, message);
 *           }
 *
 *           public static void i(String message, String tag) {
 *                  if(C.DEBUG) Log.i(tag, message);
 *           }
 *
 *    }
 *
 */

import android.util.Log;

public abstract class Logger {

    public static String DEFAULT_TAG = "GeckoWebApp";

    final static int depth = 4;

    public static void i(String message) {
        Log.i(DEFAULT_TAG, createMessage(message));
    }

    public static void i(String message, String tag) {
        Log.i(tag, createMessage(message));
    }

    public static void d(String message) {
        Log.d(DEFAULT_TAG, createMessage(message));
    }

    public static void d(String message, String tag) {
        Log.d(tag, createMessage(message));
    }

    public static void e(String message) {
        Log.e(DEFAULT_TAG, createMessage(message));
    }

    public static void e(String message, String tag) {
        Log.e(tag, createMessage(message));
    }

    public static void w(String message) {
        Log.w(DEFAULT_TAG, createMessage(message));
    }

    public static void w(String message, String tag) {
        Log.w(tag, createMessage(message));
    }

    public static void v(String message) {
        Log.v(DEFAULT_TAG, createMessage(message));
    }

    public static void v(String message, String tag) {
        Log.v(tag, createMessage(message));
    }

    public static String createMessage(String message) {
        final StackTraceElement[] ste = Thread.currentThread().getStackTrace();
        return getTrace(ste[depth]) + message;
    }

    public static String getTrace(StackTraceElement ste) {
        return "" + getClassName(ste) + "." + getMethodName(ste) + " at " + getLineNumber(ste) + ": ";
    }

    public static String getClassPackage(StackTraceElement ste) {
        return ste.getClassName();
    }

    public static String getClassName(StackTraceElement ste) {
        String[] temp = ste.getClassName().split("\\.");
        return temp[temp.length - 1];
    }

    public static String getMethodName(StackTraceElement ste) {
        return ste.getMethodName();
    }

    public static int getLineNumber(StackTraceElement ste) {
        return ste.getLineNumber();
    }

}