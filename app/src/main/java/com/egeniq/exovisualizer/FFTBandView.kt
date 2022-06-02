package com.egeniq.exovisualizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.lang.System.arraycopy
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow


/**
 * Based on FFTBandView by Pär Amsen:
 * https://github.com/paramsen/noise/blob/master/sample/src/main/java/com/paramsen/noise/sample/view/FFTBandView.kt
 *
 */
class FFTBandView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // Taken from: https://en.wikipedia.org/wiki/Preferred_number#Audio_frequencies
        private val FREQUENCY_BAND_LIMITS = arrayOf(
            20, 25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
            800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000,
            12500, 16000, 20000
        )
    }

    private val bands = FREQUENCY_BAND_LIMITS.size
    private val size = FFTAudioProcessor.SAMPLE_SIZE / 2
    private val maxConst = 25_000 // Reference max value for accum magnitude

    private val fft: FloatArray = FloatArray(size)
    private val paintBandsFill = Paint()
    private val paintBands = Paint()
    private val paintAvg = Paint()
    private val paintPath = Paint()

    // We average out the values over 3 occurences (plus the current one), so big jumps are smoothed out
    private val smoothingFactor = 3
    private val previousValues = FloatArray(bands * smoothingFactor)

    private val fftPath = Path()

    private var startedAt: Long = 0

    init {
        keepScreenOn = true
        paintBandsFill.color = Color.parseColor("#20FFFFFF")
        paintBandsFill.style = Paint.Style.FILL

        paintBands.color = Color.parseColor("#60FFFFFF")
        paintBands.strokeWidth = 1f
        paintBands.style = Paint.Style.STROKE

        paintAvg.color = Color.parseColor("#1976d2")
        paintAvg.strokeWidth = 2f
        paintAvg.style = Paint.Style.STROKE

        paintPath.color = Color.WHITE
        paintPath.strokeWidth = 8f
        paintPath.isAntiAlias = true
        paintPath.style = Paint.Style.STROKE
    }

    private fun drawAudio(canvas: Canvas) {
        // Clear the previous drawing on the screen
        canvas.drawColor(Color.TRANSPARENT)

        // Set up counters and widgets
        var currentFftPosition = 0
        var currentFrequencyBandLimitIndex = 0
        fftPath.reset()
        fftPath.moveTo(0f, height.toFloat())
        var currentAverage = 0f

        // Iterate over the entire FFT result array
        while (currentFftPosition < size) {
            var accum = 0f

            // We divide the bands by frequency.
            // Check until which index we need to stop for the current band
            val nextLimitAtPosition =
                floor(FREQUENCY_BAND_LIMITS[currentFrequencyBandLimitIndex] / 20_000.toFloat() * size).toInt()

            synchronized(fft) {
                // Here we iterate within this single band
                for (j in 0 until (nextLimitAtPosition - currentFftPosition) step 2) {
                    // Convert real and imaginary part to get energy
                    val raw = (fft[currentFftPosition + j].toDouble().pow(2.0) +
                            fft[currentFftPosition + j + 1].toDouble().pow(2.0)).toFloat()

                    // Hamming window (by frequency band instead of frequency, otherwise it would prefer 10kHz, which is too high)
                    // The window mutes down the very high and the very low frequencies, usually not hearable by the human ear
                    val m = bands / 2
                    val windowed = raw * (0.54f - 0.46f * cos(2 * Math.PI * currentFrequencyBandLimitIndex / (m + 1))).toFloat()
                    accum += windowed
                }
            }
            // A window might be empty which would result in a 0 division
            if (nextLimitAtPosition - currentFftPosition != 0) {
                accum /= (nextLimitAtPosition - currentFftPosition)
            } else {
                accum = 0.0f
            }
            currentFftPosition = nextLimitAtPosition

            // Here we do the smoothing
            // If you increase the smoothing factor, the high shoots will be toned down, but the
            // 'movement' in general will decrease too
            var smoothedAccum = accum
            for (i in 0 until smoothingFactor) {
                smoothedAccum += previousValues[i * bands + currentFrequencyBandLimitIndex]
                if (i != smoothingFactor - 1) {
                    previousValues[i * bands + currentFrequencyBandLimitIndex] =
                        previousValues[(i + 1) * bands + currentFrequencyBandLimitIndex]
                } else {
                    previousValues[i * bands + currentFrequencyBandLimitIndex] = accum
                }
            }
            smoothedAccum /= (smoothingFactor + 1) // +1 because it also includes the current value

            // We display the average amplitude with a vertical line
            currentAverage += smoothedAccum / bands


            val leftX = width * (currentFrequencyBandLimitIndex / bands.toFloat())
            val rightX = leftX + width / bands.toFloat()

            val barHeight =
                (height * (smoothedAccum / maxConst.toDouble()).coerceAtMost(1.0).toFloat())
            val top = height - barHeight

            canvas.drawRect(
                leftX,
                top,
                rightX,
                height.toFloat(),
                paintBandsFill
            )
            canvas.drawRect(
                leftX,
                top,
                rightX,
                height.toFloat(),
                paintBands
            )

            fftPath.lineTo(
                (leftX + rightX) / 2,
                top
            )

            currentFrequencyBandLimitIndex++
        }

        canvas.drawPath(fftPath, paintPath)

        canvas.drawLine(
            0f,
            height * (1 - (currentAverage / maxConst)),
            width.toFloat(),
            height * (1 - (currentAverage / maxConst)),
            paintAvg
        )
    }

    fun onFFT(fft: FloatArray) {
        synchronized(this.fft) {
            if (startedAt == 0L) {
                startedAt = System.currentTimeMillis()
            }
            // The resulting graph is mirrored, because we are using real numbers instead of imaginary
            // Explanations: https://www.mathworks.com/matlabcentral/answers/338408-why-are-fft-diagrams-mirrored
            // https://dsp.stackexchange.com/questions/4825/why-is-the-fft-mirrored/4827#4827
            // So what we do here, we only check the left part of the graph.
            arraycopy(fft, 2, this.fft, 0, size)
            // By calling invalidate, we request a redraw.
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawAudio(canvas)
        // By calling invalidate, we request a redraw. See https://github.com/dzolnai/ExoVisualizer/issues/2
        invalidate()
    }
}