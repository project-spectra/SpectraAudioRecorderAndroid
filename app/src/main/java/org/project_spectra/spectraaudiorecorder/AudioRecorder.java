package org.project_spectra.spectraaudiorecorder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Adapted from https://gist.github.com/kmark/d8b1b01fb0d2febf5770
public class AudioRecorder {

    private RecordWaveTask recordWaveTask;

    public static String HelloWorld() {
        return "Testing audio recording!";
    }

    public static String WorldHello() {
        return "WorldHello";
    }

  /*public void launchTask(String recordingPath) {
    
    //recordWaveTask = (RecordWaveTask) getLastCustomNonConfigurationInstance();
    //if (recordWaveTask == null) {
    //    recordWaveTask = new RecordWaveTask(this);
    //} else {
    //    recordWaveTask.setContext(this);
    //}

    switch (recordWaveTask.getStatus()) {
        case RUNNING:
            //Toast.makeText(this, "Task already running...", Toast.LENGTH_SHORT).show();
            return;
        case FINISHED:
            recordWaveTask = new RecordWaveTask();
            break;
        case PENDING:
            if (recordWaveTask.isCancelled()) {
                recordWaveTask = new RecordWaveTask();
            }
    }
    //File wavFile = new File(recordingPath, "recording.wav");
    //Toast.makeText(this, wavFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
    //recordWaveTask.execute(wavFile);
  }*/

    //@Override
    //public Object onRetainCustomNonConfigurationInstance() {
    //recordWaveTask.setContext(null);
    //return recordWaveTask;
    //}

    public void startTask(String recordingPath, OnFileWrittenListener cb) {
        // ensure the file actually exists
        File wavFile = new File(recordingPath);
        this.recordWaveTask = new RecordWaveTask(cb);
        this.recordWaveTask.execute(wavFile);
    }

    public void stopTask() {

        /*//Call this when user hits "Done" in NS
        if (!recordWaveTask.isCancelled() && recordWaveTask.getStatus() == AsyncTask.Status.RUNNING) {
            recordWaveTask.cancel(false);
        } else {
            //Toast.makeText(AudioRecordActivity.this, "Task not running.", Toast.LENGTH_SHORT).show();
        }*/
        if (this.recordWaveTask != null) {
            this.recordWaveTask.cancel(true);
            this.recordWaveTask = null;
        }

    }

    // We have to write the WAV header after this AsyncTask finishes.
    // Do not expose this AsyncTask directly to the NS plugin.
    public static class RecordWaveTask extends AsyncTask<File, Void, Object[]> { // <params, progress, result>

        //private Context ctx;

        //private void setContext(Context ctx) {
        //    this.ctx = ctx;
        //}

        private final int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;
        private final int SAMPLE_RATE = 44100; // Hz
        private final int ENCODING = AudioFormat.ENCODING_PCM_8BIT;
        private final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO;
        private final int BUFFER_SIZE = 2 * AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);

        private OnFileWrittenListener onFileWrittenListener;

        RecordWaveTask(OnFileWrittenListener cb) {
            this.onFileWrittenListener = cb;
        }

        /**
         * Opens up the given file, writes the header, and keeps filling it with raw PCM bytes from
         * AudioRecord until it reaches 4GB or is stopped by the user. It then goes back and updates
         * the WAV header to include the proper final chunk sizes.
         *
         * @param files Index 0 should be the file to write to
         * @return Either an Exception (error) or two longs, the filesize, elapsed time in ms (success)
         */
        @Override
        protected Object[] doInBackground(File... files) {
            AudioRecord audioRecord = null;
            FileOutputStream wavOut = null;
            long startTime = 0;
            long endTime = 0;

//            String recordingPath = strings[0];

            File wavFile = files[0];

            try {
                // Open our two resources
                audioRecord = new AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_MASK, ENCODING, BUFFER_SIZE);
                wavOut = new FileOutputStream(wavFile);

                // Write out the wav file header
                writeWavHeader(wavOut, CHANNEL_MASK, SAMPLE_RATE, ENCODING);

                // Avoiding loop allocations
                byte[] buffer = new byte[BUFFER_SIZE];
                boolean run = true;
                int read;
                long total = 0;

                // Let's go
                startTime = SystemClock.elapsedRealtime();
                audioRecord.startRecording();
                while (run && !isCancelled()) {
                    read = audioRecord.read(buffer, 0, buffer.length);

                    // WAVs cannot be > 4 GB due to the use of 32 bit unsigned integers.
                    if (total + read > 4294967295L) {
                        // Write as many bytes as we can before hitting the max size
                        for (int i = 0; i < read && total <= 4294967295L; i++, total++) {
                            wavOut.write(buffer[i]);
                        }
                        run = false;
                    } else {
                        // Write out the entire read buffer
                        wavOut.write(buffer, 0, read);
                        total += read;
                    }
                }
            } catch (IOException ex) {
                return new Object[]{ex};
            } finally {
                if (audioRecord != null) {
                    try {
                        if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            audioRecord.stop();
                            endTime = SystemClock.elapsedRealtime();
                        }
                    } catch (IllegalStateException ex) {
                        //
                    }
                    if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                        audioRecord.release();
                    }
                }
                if (wavOut != null) {
                    try {
                        wavOut.close();
                    } catch (IOException ex) {
                        //
                    }
                }
            }

            try {
                // This is not put in the try/catch/finally above since it needs to run
                // after we close the FileOutputStream
                updateWavHeader(wavFile);
            } catch (IOException ex) {
                return new Object[]{ex};
            }

            return new Object[]{wavFile.length(), endTime - startTime};
        }

        /**
         * Writes the proper 44-byte RIFF/WAVE header to/for the given stream
         * Two size fields are left empty/null since we do not yet know the final stream size
         *
         * @param out         The stream to write the header to
         * @param channelMask An AudioFormat.CHANNEL_* mask
         * @param sampleRate  The sample rate in hertz
         * @param encoding    An AudioFormat.ENCODING_PCM_* value
         * @throws IOException
         */
        private static void writeWavHeader(OutputStream out, int channelMask, int sampleRate, int encoding) throws IOException {
            short channels;
            switch (channelMask) {
                case AudioFormat.CHANNEL_IN_MONO:
                    channels = 1;
                    break;
                case AudioFormat.CHANNEL_IN_STEREO:
                    channels = 2;
                    break;
                default:
                    throw new IllegalArgumentException("Unacceptable channel mask");
            }

            short bitDepth;
            switch (encoding) {
                case AudioFormat.ENCODING_PCM_8BIT:
                    bitDepth = 8;
                    break;
                case AudioFormat.ENCODING_PCM_16BIT:
                    bitDepth = 16;
                    break;
                case AudioFormat.ENCODING_PCM_FLOAT:
                    bitDepth = 32;
                    break;
                default:
                    throw new IllegalArgumentException("Unacceptable encoding");
            }

            writeWavHeader(out, channels, sampleRate, bitDepth);
        }

        /**
         * Writes the proper 44-byte RIFF/WAVE header to/for the given stream
         * Two size fields are left empty/null since we do not yet know the final stream size
         *
         * @param out        The stream to write the header to
         * @param channels   The number of channels
         * @param sampleRate The sample rate in hertz
         * @param bitDepth   The bit depth
         * @throws IOException
         */
        private static void writeWavHeader(OutputStream out, short channels, int sampleRate, short bitDepth) throws IOException {
            // Convert the multi-byte integers to raw bytes in little endian format as required by the spec
            byte[] littleBytes = ByteBuffer
                    .allocate(14)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(channels)
                    .putInt(sampleRate)
                    .putInt(sampleRate * channels * (bitDepth / 8))
                    .putShort((short) (channels * (bitDepth / 8)))
                    .putShort(bitDepth)
                    .array();

            // Not necessarily the best, but it's very easy to visualize this way
            out.write(new byte[]{
                    // RIFF header
                    'R', 'I', 'F', 'F', // ChunkID
                    0, 0, 0, 0, // ChunkSize (must be updated later)
                    'W', 'A', 'V', 'E', // Format
                    // fmt subchunk
                    'f', 'm', 't', ' ', // Subchunk1ID
                    16, 0, 0, 0, // Subchunk1Size
                    1, 0, // AudioFormat
                    littleBytes[0], littleBytes[1], // NumChannels
                    littleBytes[2], littleBytes[3], littleBytes[4], littleBytes[5], // SampleRate
                    littleBytes[6], littleBytes[7], littleBytes[8], littleBytes[9], // ByteRate
                    littleBytes[10], littleBytes[11], // BlockAlign
                    littleBytes[12], littleBytes[13], // BitsPerSample
                    // data subchunk
                    'd', 'a', 't', 'a', // Subchunk2ID
                    0, 0, 0, 0, // Subchunk2Size (must be updated later)
            });
        }

        /**
         * Updates the given wav file's header to include the final chunk sizes
         *
         * @param wav The wav file to update
         * @throws IOException
         */
        private static void updateWavHeader(File wav) throws IOException {
            byte[] sizes = ByteBuffer
                    .allocate(8)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    // There are probably a bunch of different/better ways to calculate
                    // these two given your circumstances. Cast should be safe since if the WAV is
                    // > 4 GB we've already made a terrible mistake.
                    .putInt((int) (wav.length() - 8)) // ChunkSize
                    .putInt((int) (wav.length() - 44)) // Subchunk2Size
                    .array();

            RandomAccessFile accessWave = null;
            //noinspection CaughtExceptionImmediatelyRethrown
            try {
                accessWave = new RandomAccessFile(wav, "rw");
                // ChunkSize
                accessWave.seek(4);
                accessWave.write(sizes, 0, 4);

                // Subchunk2Size
                accessWave.seek(40);
                accessWave.write(sizes, 4, 4);
            } catch (IOException ex) {
                // Rethrow but we still close accessWave in our finally
                throw ex;
            } finally {
                if (accessWave != null) {
                    try {
                        accessWave.close();
                    } catch (IOException ex) {
                        //
                    }
                }
            }
        }

        @Override
        protected void onCancelled(Object[] results) {
            // Handling cancellations and successful runs in the same way
            Log.d(RecordWaveTask.class.getSimpleName(), "Cancelled");

            Log.d(RecordWaveTask.class.getSimpleName(), String.valueOf((long) results[0]));
            Log.d(RecordWaveTask.class.getSimpleName(), String.valueOf((long) results[1]));

            this.onFileWrittenListener.onFileWrittenCallback((long) results[0], (long) results[1]);

//            onPostExecute(results);
        }

        @Override
        protected void onPostExecute(Object[] results) {
            Log.d(RecordWaveTask.class.getSimpleName(), "PostExecute");

            Throwable throwable = null;
            if (results[0] instanceof Throwable) {
                // Error
                throwable = (Throwable) results[0];
                Log.e(RecordWaveTask.class.getSimpleName(), throwable.getMessage(), throwable);
            }


            // If we're attached to an activity
            //if (ctx != null) {
            //    if (throwable == null) {
            //        // Display final recording stats
            //        double size = (long) results[0] / 1000000.00;
            //        long time = (long) results[1] / 1000;
            //Toast.makeText(ctx, String.format(Locale.getDefault(), "%.2f MB / %d seconds",
            //size, time), Toast.LENGTH_LONG).show();
            //    } else {
            // Error
            //Toast.makeText(ctx, throwable.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            //    }
            //}
        }
    }

}
