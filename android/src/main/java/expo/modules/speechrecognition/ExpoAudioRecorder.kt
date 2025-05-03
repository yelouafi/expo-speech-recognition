package expo.modules.speechrecognition

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

interface AudioRecorder {
    fun start()

    fun stop()
}

/**
 * ExpoAudioRecorder allows us to record to a 16hz pcm stream for use in SpeechRecognition
 *
 * Once stopped, the recording stream is written to a wav file for external use
 */
class ExpoAudioRecorder(
    private val context: Context,
    // Optional output file path
    private val outputFilePath: String?,
) : AudioRecorder {
    private var audioRecorder: AudioRecord? = null

    var outputFile: File? = null
    var outputFileUri = "file://$outputFilePath"

    /** The file where the mic stream is being output to */
    private val tempPcmFile: File
    val recordingParcel: ParcelFileDescriptor
    private var outputStream: AutoCloseOutputStream?

    init {
        tempPcmFile = createTempPcmFile()
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            recordingParcel = pipe[0]
            outputStream = AutoCloseOutputStream(pipe[1])
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create pipe", e)
            e.printStackTrace()
            throw e
        }
    }

    val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSizeInBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)

    private var recordingThread: Thread? = null
    private var isRecordingAudio = false
    private var isMuted = false

    companion object {
        private const val TAG = "ExpoAudioRecorder"

        private fun shortReverseBytes(s: Short): Int =
            java.lang.Short
                .reverseBytes(s)
                .toInt()

        fun appendWavHeader(
            outputFilePath: String,
            pcmFile: File,
            sampleRateInHz: Int,
        ): File {
            val outputFile = File(outputFilePath)
            val audioDataLength = pcmFile.length()
            val numChannels = 1
            val bitsPerSample = 16

            DataOutputStream(FileOutputStream(outputFile)).use { out ->
                val totalDataLen = 36 + audioDataLength
                val byteRate = sampleRateInHz * numChannels * bitsPerSample / 8
                val blockAlign = numChannels * bitsPerSample / 8

                // Write the RIFF chunk descriptor
                out.writeBytes("RIFF") // ChunkID
                out.writeInt(Integer.reverseBytes(totalDataLen.toInt())) // ChunkSize
                out.writeBytes("WAVE")
                out.writeBytes("fmt ")
                out.writeInt(Integer.reverseBytes(16)) // Subchunk1Size (16 for PCM)
                out.writeShort(shortReverseBytes(1)) // AudioFormat (1 for PCM)
                out.writeShort(shortReverseBytes(numChannels.toShort())) // NumChannels
                out.writeInt(Integer.reverseBytes(sampleRateInHz)) // SampleRate
                out.writeInt(Integer.reverseBytes(byteRate)) // ByteRate
                out.writeShort(shortReverseBytes(blockAlign.toShort())) // BlockAlign
                out.writeShort(shortReverseBytes(bitsPerSample.toShort())) // BitsPerSample

                // Write the data sub-chunk
                out.writeBytes("data")
                out.writeInt(Integer.reverseBytes(audioDataLength.toInt()))

                try {
                    val pcmData = pcmFile.readBytes()
                    out.write(pcmData)
                    pcmFile.delete()
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to read PCM file", e)
                    e.printStackTrace()
                }
            }

            return outputFile
        }
    }

    private fun createTempPcmFile(): File {
        val file = File(context.cacheDir, "temp_${UUID.randomUUID()}.pcm")
        if (!file.exists()) {
            try {
                file.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return file
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord =
        AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            sampleRateInHz,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeInBytes,
        )

    override fun start() {
        createRecorder().apply {
            audioRecorder = this

            // First check whether the above object actually initialized
            if (this.state != AudioRecord.STATE_INITIALIZED) {
                return
            }

            this.startRecording()
            isRecordingAudio = true

            // Start thread
            recordingThread =
                thread {
                    streamAudioToPipe()
                }
        }
    }

    override fun stop() {
        isRecordingAudio = false
        audioRecorder?.stop()
        audioRecorder?.release()
        audioRecorder = null
        recordingThread = null
        if (outputFilePath != null) {
            try {
                outputFile =
                    appendWavHeader(
                        outputFilePath,
                        tempPcmFile,
                        sampleRateInHz,
                    )
            } catch (e: IOException) {
                Log.e(TAG, "Failed to append WAV header", e)
                e.printStackTrace()
            }
        }
        // Close the ParcelFileDescriptor
        try {
            recordingParcel.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        // And the output stream
        try {
            outputStream?.close()
            outputStream = null
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * Mutes the audio recording by temporarily stopping the recording stream from being processed
     * without stopping the actual recognition process.
     */
    fun mute() {
        isMuted = true
    }

    /**
     * Unmutes the audio recording, resuming the processing of the audio stream.
     */
    fun unmute() {
        isMuted = false
    }

    private fun streamAudioToPipe() {
        val tempFileOutputStream = FileOutputStream(tempPcmFile)
        val data = ByteArray(bufferSizeInBytes / 2)
        val mutedData = ByteArray(bufferSizeInBytes / 2) // Zero-filled buffer for muted audio

        while (isRecordingAudio) {
            val read = audioRecorder!!.read(data, 0, data.size)
            if (read > 0) {
                // Write to temp file for WAV conversion later
                tempFileOutputStream.write(data, 0, read)
                
                // Only write actual audio data to the recognition pipe if not muted
                if (!isMuted) {
                    outputStream?.write(data, 0, read)
                } else {
                    // When muted, we still need to write something to maintain the stream timing
                    // but we write silence (zero-filled buffer)
                    outputStream?.write(mutedData, 0, read)
                }
            }
        }
        
        // Close the temp file
        tempFileOutputStream.close()
    }
}
