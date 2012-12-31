package xitrum.util

import java.nio.charset.Charset
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.handler.codec.base64.{Base64 => B64, Base64Dialect}
import org.jboss.netty.util.CharsetUtil.UTF_8

object Base64 {
  /**
   * The result contains no padding ("=" character) so that it can be used as
   * request parameter name. (Netty POST body decoder prohibits "=" in parameter name.)
   *
   * See http://en.wikipedia.org/wiki/Base_64#Padding
   */
  def encode(bytes: Array[Byte]): String = {
    // No line break because the result may be used in HTTP response header (cookie)
    val buffer = B64.encode(ChannelBuffers.wrappedBuffer(bytes), false, Base64Dialect.URL_SAFE)
    val base64String = buffer.toString(UTF_8)
    removePadding(base64String)
  }

  def decode(base64String: String): Option[Array[Byte]] = {
    try {
      val withPadding = addPadding(base64String)
      val buffer      = B64.decode(ChannelBuffers.copiedBuffer(withPadding, UTF_8), Base64Dialect.URL_SAFE)
      Some(ChannelBufferToBytes(buffer))
    } catch {
      case scala.util.control.NonFatal(e) => None
    }
  }

  // ---------------------------------------------------------------------------

  def removePadding(base64String: String) = base64String.replace("=", "")

  def addPadding(base64String: String) = {
    val mod = base64String.length % 4
    val padding = if (mod == 0) "" else if (mod == 1) "===" else if (mod == 2) "==" else if (mod == 3) "="
    base64String + padding
  }
}
