# JNI resolves these entry points by their declared names.
-keep class dev.gf2log.app.capture.NativeCaptureBridge { *; }
-keep interface dev.gf2log.app.capture.NativeCaptureBridge$PayloadListener { *; }
-keepclassmembers class * implements dev.gf2log.app.capture.NativeCaptureBridge$PayloadListener {
    public void onPayload(long, boolean, byte[]);
    public void onFlowClosed(long);
    public void onTraffic(long, long, long);
    public void onCaptureStopped();
}
