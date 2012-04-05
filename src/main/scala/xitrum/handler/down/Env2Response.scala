package xitrum.handler.down

import io.netty.buffer.ChannelBuffers
import io.netty.channel.{ChannelHandler, SimpleChannelDownstreamHandler, ChannelHandlerContext, MessageEvent, Channels, ChannelFutureListener}
import ChannelHandler.Sharable
import io.netty.handler.codec.http.{HttpHeaders, HttpRequest, HttpResponse, HttpResponseStatus}
import HttpHeaders.Names._
import HttpResponseStatus._

import xitrum.Config
import xitrum.etag.Etag
import xitrum.handler.HandlerEnv
import xitrum.handler.up.NoPipelining
import xitrum.util.{ChannelBufferToBytes, Gzip, Mime}

@Sharable
class Env2Response extends SimpleChannelDownstreamHandler {
  override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
    val m = e.getMessage
    if (!m.isInstanceOf[HandlerEnv]) {
      ctx.sendDownstream(e)
      return
    }

    val env      = m.asInstanceOf[HandlerEnv]
    val request  = env.request
    val response = env.response
    val future   = e.getFuture

    // If HttpHeaders.getContentLength(response) != response.getContent.readableBytes,
    // it is because the response is sent in async mode.
    if (HttpHeaders.getContentLength(response) == response.getContent.readableBytes) {
      // Only effective for dynamic response, static file response has already been handled
      if (!tryEtag(request, response)) Gzip.tryCompressBigTextualResponse(request, response)
    }

    // Keep alive, channel reading resuming/closing etc. are handled
    // by the code that sends the response (Responder#respond)
    Channels.write(ctx, future, response)
  }

  //----------------------------------------------------------------------------

 /**
   * This does not make the server faster, but decrease the response transmission
   * time through the network to the browser.
   *
   * @return true if the NO_MODIFIED response is set by this method
   */
  private def tryEtag(request: HttpRequest, response: HttpResponse): Boolean = {
    if (response.getStatus != OK) return false

    val channelBuffer = response.getContent
    if (channelBuffer.readableBytes > Config.config.response.smallStaticFileSizeInKB * 1024) return false

    val etag1 = response.getHeader(ETAG)
    val etag2 = if (etag1 != null) etag1 else Etag.forBytes(ChannelBufferToBytes(channelBuffer))

    if (request.getHeader(IF_NONE_MATCH) == etag2) {
      // Only send headers, the content is empty
      response.setStatus(NOT_MODIFIED)
      HttpHeaders.setContentLength(response, 0)
      response.setContent(ChannelBuffers.EMPTY_BUFFER)
      true
    } else {
      response.setHeader(ETAG, etag2)
      false
    }
  }
}
