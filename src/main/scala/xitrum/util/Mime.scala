package xitrum.util

import java.io.File
import javax.activation.MimetypesFileTypeMap

// See
//   https://github.com/klacke/yaws/blob/master/src/mime.types
//   http://svn.apache.org/repos/asf/httpd/httpd/trunk/docs/conf/mime.types
//   http://download.oracle.com/javaee/5/api/javax/activation/MimetypesFileTypeMap.html
//   src/main/resources/META-INF/mime.types
object Mime {
  private[this] val map = new MimetypesFileTypeMap

  def get(file: String) = Option(map.getContentType(file))

  def get(file: File) = Option(map.getContentType(file))

  def isTextual(mime: String) = {
    if (mime == null) {
      false
    } else {
      val lower = mime.toLowerCase
      (lower.indexOf("text") >= 0 || lower.indexOf("xml") >= 0 || lower.indexOf("script") >= 0 || lower.indexOf("json") >= 0)
    }
  }
}
