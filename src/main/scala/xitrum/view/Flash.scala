package xitrum.view

import xitrum.Controller

object Flash {
  val FLASH_KEY = "_flash"
}

trait Flash {
  this: Controller =>

  import Flash._

  /** @see jsFlash(msg). */
  def flash(msg: Any) {
    session(FLASH_KEY) = msg
  }

  /**
   * Same as jsFlash, but for web 1.0. The flash is clear right after this method
   * is called.
   */
  def flash = {
    sessiono(FLASH_KEY) match {
      case None =>
        ""

      case Some(msg) =>
        session.remove(FLASH_KEY)
        msg
    }
  }

  //----------------------------------------------------------------------------

  /**
   * For web 2.0 style application.
   * Used in postbacks to send a message to flash area right away.
   */
  def jsFlash(msg: Any) = "xitrum.flash(" + jsEscape(msg) + ")"

  def jsRenderFlash(msg: Any) {
    val js = jsFlash(msg)
    jsRender(js)
  }

  /**
   * For web 2.0 style application.
   * Used in application layout to display the flash  message right after a view is loaded.
   */
  def jsFlash {
    val msg = flash
    if (!msg.isEmpty) jsAddToView(jsFlash(msg))
  }

  lazy val xitrumCSS = <link href={urlForResource("xitrum/xitrum.css")} type="text/css" rel="stylesheet" media="all"></link>
}
