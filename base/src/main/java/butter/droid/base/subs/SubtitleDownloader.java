package butter.droid.base.subs;

import android.os.AsyncTask;
import android.support.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.WeakReference;

import butter.droid.base.manager.vlc.PlayerManager;
import butter.droid.base.providers.media.models.Media;
import butter.droid.base.providers.subs.SubsProvider;
import butter.droid.base.torrent.StreamInfo;
import butter.droid.base.utils.FileUtils;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class SubtitleDownloader {

    private final SubsProvider subsProvider;
    private final Media media;
    private final PlayerManager playerManager;

    private String subtitleLanguage;
    private WeakReference<ISubtitleDownloaderListener> listenerReference;

    public SubtitleDownloader(@NonNull SubsProvider subsProvider, @NonNull StreamInfo streamInfo,
            PlayerManager playerManager, @NonNull String language) {

        if (language.equals(SubsProvider.SUBTITLE_LANGUAGE_NONE)) throw new IllegalArgumentException("language must be specified");

        this.subsProvider = subsProvider;
        subtitleLanguage = language;
        this.playerManager = playerManager;

        media = streamInfo.getMedia();
        if (media == null) throw new IllegalArgumentException("media from StreamInfo must not null");
    }

    public void downloadSubtitle() {
        if (listenerReference == null) throw new IllegalArgumentException("listener must not null. Call setSubtitleDownloaderListener() to sets one");
        subsProvider.download(media, subtitleLanguage, new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                onSubtitleDownloadFailed();
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                onSubtitleDownloadSuccess();
            }
        });
    }

    public void parseSubtitle(@NonNull File subtitleFile) {
        if (listenerReference == null) throw new IllegalArgumentException("listener must not null. Call setSubtitleDownloaderListener() to sets one");
        if (listenerReference.get() == null) return;
        ISubtitleDownloaderListener listener = listenerReference.get();
        SubtitleParseTask task = new SubtitleParseTask(subtitleLanguage, listener);
        task.execute(subtitleFile);
    }

    public void setSubtitleDownloaderListener(ISubtitleDownloaderListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not null");
        listenerReference = new WeakReference<>(listener);
    }

    /**
     * Invoked when subtitle download finished successfully.
     */
    private void onSubtitleDownloadSuccess() {
        if (listenerReference.get() == null) return;

        ISubtitleDownloaderListener listener = listenerReference.get();

        try {
            File subtitleFile = playerManager.getDownloadedSubtitleFile(media, subtitleLanguage);
            SubtitleParseTask task = new SubtitleParseTask(subtitleLanguage, listener);
            task.execute(subtitleFile);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            listener.onSubtitleDownloadCompleted(false, null);
        }
    }

    /**
     * Invoked when subtitle download failed.
     */
    private void onSubtitleDownloadFailed() {
        subtitleLanguage = SubsProvider.SUBTITLE_LANGUAGE_NONE;
        if (listenerReference.get() == null) return;
        ISubtitleDownloaderListener listener = listenerReference.get();
        listener.onSubtitleDownloadCompleted(false, null);
    }

    private class SubtitleParseTask extends AsyncTask<File, TimedTextObject, TimedTextObject> {
        String subtitleLanguage;
        WeakReference<ISubtitleDownloaderListener> listenerReference;

        public SubtitleParseTask(String language, ISubtitleDownloaderListener listener) {
            subtitleLanguage = language;
            listenerReference = new WeakReference<>(listener);
        }

        @Override
        protected TimedTextObject doInBackground(File... files) {
            for (File file : files) {
                try {
                    TimedTextObject text = parseAsTimedTextObject(file);
                    publishProgress(text);
                }
                catch (FileNotFoundException e) {
                    if (e.getMessage().contains("EBUSY")) {
                        try {
                            TimedTextObject text = parseAsTimedTextObject(file);
                            publishProgress(text);
                        } catch (IOException e1) {
                            e1.printStackTrace();
                            return null;
                        }
                    }
                    e.printStackTrace();
                }
                catch (IOException e) {
                    e.printStackTrace();
                    return null;
                }
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(TimedTextObject... values) {
            super.onProgressUpdate(values);
            for (TimedTextObject timedTextObject : values ) {
                if (listenerReference.get() == null) break;
                listenerReference.get().onSubtitleDownloadCompleted(true, timedTextObject);
            }
        }

        private TimedTextObject parseAsTimedTextObject(File file) throws IOException {
            FileInputStream fileInputStream = new FileInputStream(file);
            FormatSRT formatSRT = new FormatSRT();
            TimedTextObject result = formatSRT.parseFile(
                    file.toString(),
                    FileUtils.inputstreamToCharsetString(
                            fileInputStream,
                            subtitleLanguage).split("\n"));
            return result;
        }
    }

    public interface ISubtitleDownloaderListener {
        void onSubtitleDownloadCompleted(boolean isSuccessful, TimedTextObject subtitleFile);
    }
}
