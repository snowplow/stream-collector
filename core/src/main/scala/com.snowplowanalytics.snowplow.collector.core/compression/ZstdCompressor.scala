/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
  * located at https://docs.snowplow.io/limited-use-license-1.1
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collector.core.compression

import com.github.luben.zstd.ZstdOutputStreamNoFinalizer

import java.io.OutputStream

class ZstdCompressor(compressionLevel: Int, targetSize: Int) extends Compressor(targetSize) {

  override protected val compressor: OutputStream = new ZstdOutputStreamNoFinalizer(rwos, compressionLevel)

  override protected def extraBytesNeededForFooter: Int = 3

  compressor.asInstanceOf[ZstdOutputStreamNoFinalizer].setChecksum(false)

}

object ZstdCompressor {

  def factory(compressionLevel: Int): Compressor.Factory = new Compressor.Factory {
    protected def build(targetSize: Int): Compressor =
      new ZstdCompressor(compressionLevel, targetSize)
  }

}
