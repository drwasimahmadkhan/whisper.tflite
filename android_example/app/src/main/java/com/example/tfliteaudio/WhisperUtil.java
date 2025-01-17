package com.example.tfliteaudio;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.Map;

import static java.lang.Math.cos;
import static java.lang.Math.log10;
import static java.lang.Math.sin;

public class WhisperUtil {

    private static final String TAG = "WhisperUtil";

    public static final int WHISPER_SAMPLE_RATE = 16000;
    public static final int WHISPER_N_FFT = 400;
    public static final int WHISPER_N_MEL = 80;
    public static final int WHISPER_HOP_LENGTH = 160;
    public static final int WHISPER_CHUNK_SIZE = 30;
    public static final int WHISPER_MEL_LEN = 3000;
    public static final int[] golden_generated_ids = {
            50257, 50362, 1770, 13, 2264, 346, 353, 318,
            262, 46329, 286, 262, 3504, 6097, 11, 290, 356, 389, 9675, 284, 7062
    };

    private static final AtomicInteger threadCounter = new AtomicInteger(0);

    WhisperVocab vocab = new WhisperVocab();
    WhisperFilter filters = new WhisperFilter();
    WhisperMel mel = new WhisperMel();

    // Helper class definitions
    public static class WhisperVocab {
        // Token types
        public int tokenEot = 50256; // end of transcript
        public int tokenSot = 50257; // start of transcript
        public int tokenPrev = 50360;
        public int tokenSolm = 50361; // ??
        public int tokenNot = 50362; // no timestamps
        public int tokenBeg = 50363;

        // Available tasks
        public int tokenTranslate = 50358;
        public int tokenTranscribe = 50359;

        public Map<Integer, String> tokenToWord = new HashMap<>();

        // Vocab types
        public int nVocabMultilingual = 51865;    // for multilingual vocab
        public int nVocabNonMultilingual = 51864; // for non multilingual vocab

        // Initialise nVocab as default types
        public int nVocab = nVocabMultilingual;

        public void setMultilingual(boolean multilingual) {
            if (multilingual)
                nVocab = nVocabMultilingual;
            else
                nVocab = nVocabNonMultilingual;
        }

        public boolean isMultilingual() {
            return nVocab == nVocabMultilingual;
        }
    }

    public static class WhisperFilter {
        public int nMel = 0;
        public int nFft = 0;
        public float[] data;
    }

    public static class WhisperMel {
        public int nLen = 0;
        public int nMel = 0;
        public float[] data;
    }

    // Helper functions definitions
    public String getWordFromToken(int token) {
        return vocab.tokenToWord.get(token);
    }

    // nSamples => WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SIZE => 480000
    public static boolean getMelSpectrogram(
            float[] samples, int nSamples, int sampleRate,
            int fftSize, int fftStep, int nMel, int nThreads,
            WhisperFilter filters, WhisperMel mel) {

        mel.nMel = nMel;
        mel.nLen = nSamples / fftStep;
        mel.data = new float[mel.nMel * mel.nLen];

        float[] hann = new float[fftSize];
        for (int i = 0; i < fftSize; i++) {
            hann[i] = (float) (0.5 * (1.0 - cos(2.0 * Math.PI * i / fftSize)));
        }

        int nFft = 1 + fftSize / 2;
        Thread[] workers = new Thread[nThreads];
        for (int iw = 0; iw < nThreads; iw++) {
            final int ith = threadCounter.incrementAndGet();
            workers[iw] = new Thread(() -> {
                float[] fftIn = new float[fftSize];
                for (int i = 0; i < fftSize; i++) {
                    fftIn[i] = 0.0f;
                }

                float[] fftOut = new float[fftSize * 2];
                int i = ith;
                while (i < mel.nLen) {
                    int offset = i * fftStep;

                    // apply Hanning window
                    for (int j = 0; j < fftSize; j++) {
                        if (offset + j < nSamples) {
                            fftIn[j] = hann[j] * samples[offset + j];
                        } else {
                            fftIn[j] = 0.0f;
                        }
                    }

                    // FFT -> mag^2
                    fft(fftIn, fftOut);
                    for (int j = 0; j < fftSize; j++) {
                        fftOut[j] = fftOut[2 * j] * fftOut[2 * j] + fftOut[2 * j + 1] * fftOut[2 * j + 1];
                    }

                    for (int j = 0; j < fftSize / 2; j++) {
                        fftOut[j] += fftOut[fftSize - j - 1];
                    }

                    // mel spectrogram
                    for (int j = 0; j < mel.nMel; j++) {
                        double sum = 0.0;
                        for (int k = 0; k < nFft; k++) {
                            sum += (fftOut[k] * filters.data[j * nFft + k]);
                        }

                        if (sum < 1e-10) {
                            sum = 1e-10;
                        }

                        sum = log10(sum);
                        mel.data[j * mel.nLen + i] = (float) sum;
                    }
                    i += nThreads;
                }
            });
            workers[iw].start();
        }

        for (int iw = 0; iw < nThreads; iw++) {
            try {
                workers[iw].join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // clamping and normalization
        double mmax = -1e20;
        for (int i = 0; i < mel.nMel * mel.nLen; i++) {
            if (mel.data[i] > mmax) {
                mmax = mel.data[i];
            }
        }

        mmax -= 8.0;
        for (int i = 0; i < mel.nMel * mel.nLen; i++) {
            if (mel.data[i] < mmax) {
                mel.data[i] = (float) mmax;
            }
            mel.data[i] = (float) ((mel.data[i] + 4.0) / 4.0);
        }

        return true;
    }

    private static void dft(float[] input, float[] output) {
        int inSize = input.length;
        for (int k = 0; k < inSize; k++) {
            float re = 0.0f;
            float im = 0.0f;
            for (int n = 0; n < inSize; n++) {
                float angle = (float) (2 * Math.PI * k * n / inSize);
                re += input[n] * cos(angle);
                im -= input[n] * sin(angle);
            }
            output[k * 2 + 0] = re;
            output[k * 2 + 1] = im;
        }
    }

    private static void fft(float[] input, float[] output) {
        int inSize = input.length;
        if (inSize == 1) {
            output[0] = input[0];
            output[1] = 0.0f;
            return;
        }

        if (inSize % 2 == 1) {
            dft(input, output);
            return;
        }

        float[] even = new float[inSize / 2];
        float[] odd = new float[inSize / 2];

        int indxEven = 0;
        int indxOdd = 0;
        for (int i = 0; i < inSize; i++) {
            if (i % 2 == 0) {
                even[indxEven] = input[i];
                indxEven++;
            } else {
                odd[indxOdd] = input[i];
                indxOdd++;
            }
        }

        float[] evenFft = new float[inSize];
        float[] oddFft = new float[inSize];

        fft(even, evenFft);
        fft(odd, oddFft);
        for (int k = 0; k < inSize / 2; k++) {
            float theta = (float) (2 * Math.PI * k / inSize);
            float re = (float) cos(theta);
            float im = (float) -sin(theta);
            float reOdd = oddFft[2 * k + 0];
            float imOdd = oddFft[2 * k + 1];
            output[2 * k + 0] = evenFft[2 * k + 0] + re * reOdd - im * imOdd;
            output[2 * k + 1] = evenFft[2 * k + 1] + re * imOdd + im * reOdd;
            output[2 * (k + inSize / 2) + 0] = evenFft[2 * k + 0] - re * reOdd + im * imOdd;
            output[2 * (k + inSize / 2) + 1] = evenFft[2 * k + 1] - re * imOdd - im * reOdd;
        }
    }
}
