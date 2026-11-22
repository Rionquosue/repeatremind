package style.spacedragon.repeatremind;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Audible reminders. A channel's sound is fixed once created, so each sound gets its own channel, shared by
 * every task that uses it. Sounds play at alarm volume, so they are heard with the ringer silenced.
 */
final class TaskSounds {
    private static final String PREFIX = "task_sound_";

    private TaskSounds() { }

    static Uri uri(Task task) {
        return task.sound == null || task.sound.isEmpty() ? Settings.System.DEFAULT_ALARM_ALERT_URI : Uri.parse(task.sound);
    }

    static String title(Context context, Uri sound) {
        if (Settings.System.DEFAULT_ALARM_ALERT_URI.equals(sound)) return "Default alarm";
        try {
            Ringtone ringtone = RingtoneManager.getRingtone(context, sound);
            return ringtone == null ? "Custom sound" : ringtone.getTitle(context);
        } catch (RuntimeException e) {
            return "Custom sound"; // Some sounds cannot be named without storage access.
        }
    }

    private static String channelId(Task task) { return PREFIX + Integer.toHexString(uri(task).toString().hashCode()); }

    /** The channel for an audible task's reminders, created on first use. */
    static String channel(Context context, Task task) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String id = channelId(task);
        if (manager.getNotificationChannel(id) == null) {
            Uri sound = uri(task);
            NotificationChannel channel = new NotificationChannel(id,
                    "Audible reminders · " + title(context, sound), NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Task reminders that play an alarm sound");
            channel.setSound(sound, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
        }
        return id;
    }

    /** Deletes sound channels no task uses any more, so the system settings list only live ones. */
    static void prune(Context context, List<Task> tasks) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Set<String> used = new HashSet<>();
        for (Task task : tasks) if (task.audible) used.add(channelId(task));
        for (NotificationChannel channel : manager.getNotificationChannels()) {
            if (channel.getId().startsWith(PREFIX) && !used.contains(channel.getId())) {
                manager.deleteNotificationChannel(channel.getId());
            }
        }
    }
}
