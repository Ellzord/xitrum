package xitrum.routing

import io.netty.handler.codec.http.HttpMethod

// To simplify the routing code, take WebSocket as a HttpMethod
object HttpMethodWebSocket extends HttpMethod("WEBSOCKET")
