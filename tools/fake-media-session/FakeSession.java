import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Looper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** A silent media session for testing the launcher's now-playing card: a "playing" track with art
 *  and no audio, owned by the shell. Run with app_process; stop with kill. Args: title artist [paused] */
public class FakeSession {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = at.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object thread = ctor.newInstance();
        Field cur = at.getDeclaredField("sCurrentActivityThread");
        cur.setAccessible(true);
        cur.set(null, thread);
        Field sys = at.getDeclaredField("mSystemThread");
        sys.setAccessible(true);
        sys.setBoolean(thread, true);
        Context system = (Context) at.getDeclaredMethod("getSystemContext").invoke(thread);
        final Object[] manager = new Object[1];
        Context shell = new ContextWrapper(system) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public Context getApplicationContext() { return this; }
            // The media service checks the package against the caller (the shell), so its manager
            // must be made with this context, not the system one.
            @Override public Object getSystemService(String name) {
                if (Context.MEDIA_SESSION_SERVICE.equals(name)) return manager[0];
                return super.getSystemService(name);
            }
        };
        Constructor<?> msm = Class.forName("android.media.session.MediaSessionManager").getDeclaredConstructor(Context.class);
        msm.setAccessible(true);
        manager[0] = msm.newInstance(shell);
        Bitmap art = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 300, 300, 0xFF1DB954, 0xFF2B2F77, Shader.TileMode.CLAMP));
        new Canvas(art).drawRect(0, 0, 300, 300, p);
        MediaSession s = new MediaSession(shell, "glass-test");
        s.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, args.length > 0 ? args[0] : "Test track")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, args.length > 1 ? args[1] : "Test artist")
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            .build());
        boolean paused = args.length > 2 && args[2].equals("paused");
        s.setPlaybackState(new PlaybackState.Builder()
            .setState(paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING, 0, paused ? 0f : 1f)
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
            .build());
        s.setCallback(new MediaSession.Callback() {
            @Override public void onPause() { System.out.println("pause pressed"); }
            @Override public void onPlay() { System.out.println("play pressed"); }
        });
        s.setActive(true);
        System.out.println("session up");
        Looper.loop();
    }
}
