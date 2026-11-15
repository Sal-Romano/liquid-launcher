# fake-media-session: a silent "now playing" for testing

The launcher's now-playing card shows whatever app has a playing media session. To see it without
playing anything out loud, this registers a session as the adb shell: a track with art, in the
playing state, and no audio. It is not part of the app.

```sh
SDK=$HOME/Library/Android/sdk
mkdir -p /tmp/fms && javac -source 11 -target 11 -cp $SDK/platforms/android-36/android.jar -d /tmp/fms FakeSession.java
$SDK/build-tools/36.1.0/d8 --min-api 30 --output /tmp/fms /tmp/fms/*.class   # needs Java 17 on the PATH
adb push /tmp/fms/classes.dex /data/local/tmp/fakesession.dex
adb shell 'CLASSPATH=/data/local/tmp/fakesession.dex app_process / FakeSession "Some song" "Some artist"' &
# ... look at the TV; Play/Pause on the remote prints "pause pressed" here ...
adb shell pkill -f FakeSession; adb shell rm /data/local/tmp/fakesession.dex
```

A third argument `paused` registers it paused instead. The card then reads "Shell" as the app, the
shell being the session's owner. How it works: app_process runs the class with a system context,
and the media service checks that a session's package belongs to its caller, so the context claims
`com.android.shell` and the MediaSessionManager is built from it (the system's own would name
`android` and be refused). Checked on the Hisense, Fire OS 8.
