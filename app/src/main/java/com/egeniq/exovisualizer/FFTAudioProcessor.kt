package com.egeniq.exovisualizer

import android.media.AudioTrack
import android.media.AudioTrack.ERROR_BAD_VALUE
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.util.Assertions
import com.google.android.exoplayer2.util.Util
import com.paramsen.noise.Noise
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An audio processor which forwards the input to the output,
 * but also takes the input and executes a Fast-Fourier Transformation (FFT) on it.
 * The results of this transformation is a 'list' of frequencies with their amplitudes,
 * which will be forwarded to the listener
 */
class FFTAudioProcessor : AudioProcessor {

    companion object {
        const val SAMPLE_SIZE = 4096

        // From DefaultAudioSink.java:160 'MIN_BUFFER_DURATION_US'
        private const val EXO_MIN_BUFFER_DURATION_US: Long = 250000
        // From DefaultAudioSink.java:164 'MAX_BUFFER_DURATION_US'
        private const val EXO_MAX_BUFFER_DURATION_US: Long = 750000
        // From DefaultAudioSink.java:173 'BUFFER_MULTIPLICATION_FACTOR'
        private const val EXO_BUFFER_MULTIPLICATION_FACTOR = 4

        // Extra size next in addition to the AudioTrack buffer size
        private const val BUFFER_EXTRA_SIZE = SAMPLE_SIZE * 8
    }

    private var noise: Noise? = null

    private var sampleRateHz: Int = 0
    private var channelCount: Int = 0
    @C.Encoding
    private var encoding: Int = 0
    private var isActive: Boolean = false

    private var processBuffer: ByteBuffer
    private var fftBuffer: ByteBuffer
    private var outputBuffer: ByteBuffer? = null

    var listener: FFTListener? = null
    private var inputEnded: Boolean = false

    private lateinit var srcBuffer: ByteBuffer
    private var srcBufferPosition = 0
    private val tempByteArray = ByteArray(SAMPLE_SIZE * 2)

    private var audioTrackBufferSize = 0

    private val src = FloatArray(SAMPLE_SIZE)
    private val dst = FloatArray(SAMPLE_SIZE + 2)


    interface FFTListener {
        fun onFFTReady(sampleRateHz: Int, channelCount: Int, fft: FloatArray)
    }

    init {
        processBuffer = AudioProcessor.EMPTY_BUFFER
        fftBuffer = AudioProcessor.EMPTY_BUFFER
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        channelCount = Format.NO_VALUE
        sampleRateHz = Format.NO_VALUE

    }

    /**
     * The following method matches the implementation of getDefaultBufferSize in DefaultAudioSink
     * of ExoPlayer.
     * Because there is an AudioTrack buffer between the processor and the sound output, the processor receives everything early.
     * By putting the audio data to process in a buffer which has the same size as the audiotrack buffer,
     * we will delay ourselves to match the audio output.
     */
    private fun getDefaultBufferSizeInBytes(): Int {
        val outputPcmFrameSize = Util.getPcmFrameSize(encoding, channelCount)
        val minBufferSize =
            AudioTrack.getMinBufferSize(
                sampleRateHz,
                Util.getAudioTrackChannelConfig(channelCount),
                outputEncoding
            )
        Assertions.checkState(minBufferSize != ERROR_BAD_VALUE)
        val multipliedBufferSize = minBufferSize * EXO_BUFFER_MULTIPLICATION_FACTOR
        val minAppBufferSize =
            durationUsToFrames(EXO_MIN_BUFFER_DURATION_US).toInt() * outputPcmFrameSize
        val maxAppBufferSize = Math.max(
            minBufferSize.toLong(),
            durationUsToFrames(EXO_MAX_BUFFER_DURATION_US) * outputPcmFrameSize
        ).toInt()
        val bufferSizeInFrames = Util.constrainValue(
            multipliedBufferSize,
            minAppBufferSize,
            maxAppBufferSize
        ) / outputPcmFrameSize
        return bufferSizeInFrames * outputPcmFrameSize
    }

    private fun durationUsToFrames(durationUs: Long): Long {
        return durationUs * sampleRateHz / C.MICROS_PER_SECOND
    }


    override fun configure(
        sampleRateHz: Int,
        channelCount: Int, @C.Encoding encoding: Int
    ): Boolean {
        if (encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledFormatException(sampleRateHz, channelCount, encoding)
        }
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
        this.encoding = encoding
        val wasActive = isActive
        isActive = true

        noise = Noise.real(SAMPLE_SIZE)

        audioTrackBufferSize = getDefaultBufferSizeInBytes()

        srcBuffer = ByteBuffer.allocate(audioTrackBufferSize + BUFFER_EXTRA_SIZE)

        return !wasActive
    }

    override fun isActive(): Boolean {
        return isActive
    }

    override fun getOutputChannelCount(): Int {
        return channelCount
    }

    override fun getOutputEncoding(): Int {
        return encoding
    }

    override fun getOutputSampleRateHz(): Int {
        return sampleRateHz
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        var position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val frameCount = (limit - position) / (2 * channelCount)
        val singleChannelOutputSize = frameCount * 2
        val outputSize = frameCount * channelCount * 2


        if (processBuffer.capacity() < outputSize) {
            processBuffer = ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder())
        } else {
            processBuffer.clear()
        }

        if (fftBuffer.capacity() < singleChannelOutputSize) {
            fftBuffer =
                ByteBuffer.allocateDirect(singleChannelOutputSize).order(ByteOrder.nativeOrder())
        } else {
            fftBuffer.clear()
        }

        while (position < limit) {
            var summedUp = 0
            for (channelIndex in 0 until channelCount) {
                val current = inputBuffer.getShort(position + 2 * channelIndex)
                processBuffer.putShort(current)
                summedUp += current
            }
            // For the FFT, we use an currentAverage of all the channels
            fftBuffer.putShort((summedUp / channelCount).toShort())
            position += channelCount * 2
        }

        inputBuffer.position(limit)

        processFFT(this.fftBuffer)

        processBuffer.flip()
        outputBuffer = this.processBuffer
    }

    private fun processFFT(buffer: ByteBuffer) {
        if (listener == null) {
            return
        }
        srcBuffer.put(buffer.array())
        srcBufferPosition += buffer.array().size
        // Since this is PCM 16 bit, each sample will be 2 bytes.
        // So to get the sample size in the end, we need to take twice as many bytes off the buffer
        val bytesToProcess = SAMPLE_SIZE * 2
        var currentByte : Byte? = null
        while (srcBufferPosition > audioTrackBufferSize) {
            srcBuffer.position(0)
            srcBuffer.get(tempByteArray, 0, bytesToProcess)

            tempByteArray.forEachIndexed { index, byte ->
                if (currentByte == null) {
                    currentByte = byte
                } else {
                    src[index / 2] = (currentByte!!.toFloat() * Byte.MAX_VALUE + byte) / (Byte.MAX_VALUE * Byte.MAX_VALUE)
                    dst[index / 2] = 0f
                    currentByte = null
                }

            }
            srcBuffer.position(bytesToProcess)
            srcBuffer.compact()
            srcBufferPosition -= bytesToProcess
            srcBuffer.position(srcBufferPosition)
            val fft = noise?.fft(src, dst)!!
            listener?.onFFTReady(sampleRateHz, channelCount, fft)
        }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer? {
        val outputBuffer = this.outputBuffer
        this.outputBuffer = AudioProcessor.EMPTY_BUFFER
        return outputBuffer
    }

    override fun isEnded(): Boolean {
        return inputEnded && processBuffer === AudioProcessor.EMPTY_BUFFER
    }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        // A new stream is incoming.
    }

    override fun reset() {
        flush()
        processBuffer = AudioProcessor.EMPTY_BUFFER
        sampleRateHz = Format.NO_VALUE
        channelCount = Format.NO_VALUE
        encoding = Format.NO_VALUE
    }
}