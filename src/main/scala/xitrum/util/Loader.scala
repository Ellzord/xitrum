package xitrum.util

import java.io.{FileInputStream, InputStream}
import java.util.Properties

import com.codahale.jerkson.Json
import org.jboss.netty.util.CharsetUtil.UTF_8

object Loader {
  private[this] val BUFFER_SIZE = 1024

  /** The input stream will be closed by this method after reading */
  def bytesFromInputStream(is: InputStream): Array[Byte] = {
    if (is == null) return null

    var ret    = Array[Byte]()
    var buffer = new Array[Byte](BUFFER_SIZE)
    while (is.available > 0) {  // "available" is not always the exact size
      val bytesRead = is.read(buffer)
      ret = ret ++ buffer.take(bytesRead)
    }
    is.close()
    ret
  }

  //----------------------------------------------------------------------------

  def bytesFromFile(path: String): Array[Byte] = {
    val is = new FileInputStream(path)
    bytesFromInputStream(is)
  }

  /**
   * @param path Relative to one of the elements in classpath, without leading "/"
   */
  def bytesFromClasspath(path: String): Array[Byte] = {
    val is = getClass.getClassLoader.getResourceAsStream(path)
    bytesFromInputStream(is)
  }

  //----------------------------------------------------------------------------

  def stringFromFile(path: String) =
    new String(bytesFromFile(path), UTF_8)

  /**
   * @param path Relative to one of the elements in classpath, without leading "/"
   */
  def stringFromClasspath(path: String) =
    new String(bytesFromClasspath(path), UTF_8)

  //----------------------------------------------------------------------------

  def propertiesFromFile(path: String): Properties = {
    val is  = new FileInputStream(path)
    val ret = new Properties
    ret.load(is)
    is.close()
    ret
  }

  /**
   * @param path Relative to one of the elements in classpath, without leading "/"
   */
  def propertiesFromClasspath(path: String): Properties = {
    // http://www.javaworld.com/javaworld/javaqa/2003-08/01-qa-0808-property.html?page=2
    val is = getClass.getClassLoader.getResourceAsStream(path)
    val ret = new Properties
    ret.load(is)
    is.close()
    ret
  }

  //----------------------------------------------------------------------------

  def jsonFromFile[T](path: String)(implicit m: Manifest[T]) =
    Json.parse[T](stringFromFile(path))

  /**
   * @param path Relative to one of the elements in classpath, without leading "/"
   */
  def jsonFromClasspath[T](path: String)(implicit m: Manifest[T]) =
    Json.parse[T](stringFromClasspath(path))
}
