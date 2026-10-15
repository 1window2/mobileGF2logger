# JNI resolves these entry points by their declared names.
-keep class dev.gf2log.app.capture.NativeCaptureBridge { *; }
-keep interface dev.gf2log.app.capture.NativeCaptureBridge$PayloadListener { *; }
-keepclassmembers class * implements dev.gf2log.app.capture.NativeCaptureBridge$PayloadListener {
    public void onFlowOpened(long, int, java.lang.String, int, java.lang.String, int);
    public void onPayload(long, byte[]);
    public void onFlowClosed(long);
    public void onTraffic(long, long, long);
    public void onCaptureStopped();
}
