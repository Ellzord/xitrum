package xitrum.handler.up

import scala.collection.mutable.{ArrayBuffer, Map => MMap}

import io.netty.channel._
import io.netty.handler.codec.http._
import ChannelHandler.Sharable
import HttpResponseStatus._
import HttpVersion._

import xitrum.{Action, SkipCSRFCheck, Cache, Config, Logger}
import xitrum.routing.{Routes, PostbackAction}
import xitrum.exception.{InvalidAntiCSRFToken, MissingParam, SessionExpired}
import xitrum.handler.HandlerEnv
import xitrum.handler.down.ResponseCacher
import xitrum.handler.down.XSendFile
import xitrum.routing.WebSocketHttpMethod
import xitrum.scope.request.RequestEnv
import xitrum.scope.session.CSRF

object Dispatcher extends Logger {
  def dispatchWithFailsafe(actionClass: Class[_ <: Action], env: HandlerEnv, postback: Boolean) {
    val beginTimestamp = System.currentTimeMillis
    var hit            = false

    val action = actionClass.newInstance
    action(env)
    action.setPostback(postback)
    action.handlerEnv.action = action

    try {
      // Check for CSRF (CSRF has been checked if "postback" is true)
      if (!postback &&
          action.request.getMethod != HttpMethod.GET &&
          action.request.getMethod != WebSocketHttpMethod &&
          !action.isInstanceOf[SkipCSRFCheck] &&
          !CSRF.isValidToken(action)) throw new InvalidAntiCSRFToken

      val cacheSecs = Routes.getCacheSecs(actionClass)

      if (cacheSecs > 0) {             // Page cache
        tryCache(action) {
          val passed = action.callBeforeFilters
          if (passed) runAroundAndAfterFilters(action, postback)
        }
      } else {
        val passed = action.callBeforeFilters
        if (passed) {
          if (cacheSecs == 0) {        // No cache
            runAroundAndAfterFilters(action, postback)
          } else if (cacheSecs < 0) {  // Action cache
            tryCache(action) { runAroundAndAfterFilters(action, postback) }
          }
        }
      }

      logAccess(action, postback, beginTimestamp, cacheSecs, hit)
    } catch {
      case e =>
        // End timestamp
        val t2 = System.currentTimeMillis

        // These exceptions are special cases:
        // We know that the exception is caused by the client (bad request)
        if (e.isInstanceOf[SessionExpired] || e.isInstanceOf[InvalidAntiCSRFToken] || e.isInstanceOf[MissingParam]) {
          logAccess(action, postback, beginTimestamp, 0, false)

          action.response.setStatus(BAD_REQUEST)
          val msg = if (e.isInstanceOf[SessionExpired] || e.isInstanceOf[InvalidAntiCSRFToken]) {
            action.resetSession
            "Session expired. Please refresh your browser."
          } else if (e.isInstanceOf[MissingParam]) {
            val mp  = e.asInstanceOf[MissingParam]
            val key = mp.key
            "Missing Param: " + key
          }
          if (action.isAjax)
            action.jsRender("alert(" + action.jsEscape(msg) + ")")
          else
            action.renderText(msg)
        } else {
          logAccess(action, postback, beginTimestamp, 0, false, e)

          if (Config.isProductionMode) {
            if (action.isAjax) {
              action.response.setStatus(INTERNAL_SERVER_ERROR)
              action.jsRender("alert(" + action.jsEscape("Internal Server Error") + ")")
            } else {
              if (Config.action500 == null) {
                val response = new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR)
                XSendFile.set500Page(response)
                action.handlerEnv.response = response
                action.channel.write(action.handlerEnv)
              } else {
                action.response.setStatus(INTERNAL_SERVER_ERROR)
                action.forward(Config.action500, false)
              }
            }
          } else {
            action.response.setStatus(INTERNAL_SERVER_ERROR)
            if (action.isAjax) {
              action.jsRender("alert(" + action.jsEscape(e.toString + "\n\n" + e.getStackTraceString) + ")")
            } else {
              action.renderText(e.toString + "\n\n" + e.getStackTraceString)
            }
          }
        }
    }
  }

  //----------------------------------------------------------------------------

  /** @return true if the cache was hit */
  private def tryCache(action: Action)(f: => Unit): Boolean = {
    ResponseCacher.getCachedResponse(action) match {
      case None =>
        f
        false

      case Some(response) =>
        action.channel.write(response)
        true
    }
  }

  private def runAroundAndAfterFilters(action: Action, postback: Boolean) {
    val executeOrPostback = if (postback) action.postback _ else action.execute _
    action.callAroundFilters(executeOrPostback)
    action.callAfterFilters
  }

  private def logAccess(action: Action, postback: Boolean, beginTimestamp: Long, cacheSecs: Int, hit: Boolean, e: Throwable = null) {
    // PostbackAction is a gateway, skip it to avoid noisy log if there is no error
    if (action.isInstanceOf[PostbackAction] && e == null) return

    def msgWithTime = {
      val endTimestamp = System.currentTimeMillis
      val dt           = endTimestamp - beginTimestamp

      (if (postback) "POSTBACK" else action.request.getMethod) + " " + action.getClass.getName                                                                                                         +
      (if (!action.handlerEnv.uriParams.isEmpty)        ", uriParams: "        + RequestEnv.inspectParamsWithFilter(action.handlerEnv.uriParams.asInstanceOf[MMap[String, List[Any]]])        else "") +
      (if (!action.handlerEnv.bodyParams.isEmpty)       ", bodyParams: "       + RequestEnv.inspectParamsWithFilter(action.handlerEnv.bodyParams.asInstanceOf[MMap[String, List[Any]]])       else "") +
      (if (!action.handlerEnv.pathParams.isEmpty)       ", pathParams: "       + RequestEnv.inspectParamsWithFilter(action.handlerEnv.pathParams.asInstanceOf[MMap[String, List[Any]]])       else "") +
      (if (!action.handlerEnv.fileUploadParams.isEmpty) ", fileUploadParams: " + RequestEnv.inspectParamsWithFilter(action.handlerEnv.fileUploadParams.asInstanceOf[MMap[String, List[Any]]]) else "") +
      ", " + dt + " [ms]"
    }

    def extraInfo = {
      if (cacheSecs == 0) {
        if (action.isResponded) "" else " (async)"
      } else {
        if (hit) {
          if (cacheSecs < 0) " (action cache hit)"  else " (page cache hit)"
        } else {
          if (cacheSecs < 0) " (action cache miss)" else " (page cache miss)"
        }
      }
    }

    if (e == null) {
      if (logger.isDebugEnabled) logger.debug(msgWithTime + extraInfo)
    } else {
      if (logger.isErrorEnabled) logger.error("Dispatching error " + msgWithTime + extraInfo, e)
    }
  }
}

@Sharable
class Dispatcher extends SimpleChannelUpstreamHandler with BadClientSilencer {
  import Dispatcher._

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val m = e.getMessage
    if (!m.isInstanceOf[HandlerEnv]) {
      ctx.sendUpstream(e)
      return
    }

    val env        = m.asInstanceOf[HandlerEnv]
    val request    = env.request
    val pathInfo   = env.pathInfo
    val uriParams  = env.uriParams
    val bodyParams = env.bodyParams

    Routes.matchRoute(request.getMethod, pathInfo) match {
      case Some((method, actionClass, pathParams)) =>
        request.setMethod(method)  // Override
        env.pathParams = pathParams
        dispatchWithFailsafe(actionClass, env, false)

      case None =>
        if (Config.action404 == null) {
          val response = new DefaultHttpResponse(HTTP_1_1, NOT_FOUND)
          XSendFile.set404Page(response)
          env.response = response
          ctx.getChannel.write(env)
        } else {
          env.pathParams = MMap.empty
          env.response.setStatus(NOT_FOUND)
          dispatchWithFailsafe(Config.action404, env, false)
        }
    }
  }
}
