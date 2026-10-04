package com.radiosport.ninegradio.dsp

/** Driver compatibility constants, copied unchanged from upstream. No audio/FFT engine. */
class DspEngine private constructor() {
    companion object {
        const val USB_STREAMING_BUF = 32_768
        const val DSP_CHUNK_SIZE = 16_384
    }
}
