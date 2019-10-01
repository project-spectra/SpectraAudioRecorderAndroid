package org.project_spectra.spectraaudiorecorder;

public interface OnFileWrittenListener {
    void onFileWrittenCallback(long fileSize, long elapsedMs);
}
