package org.dtb.servers;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.dtb.servers.exceptions.SecurityException;
import org.dtb.servers.exceptions.SeverRuntimeExecption;
import org.dtb.servers.jwt.AuthRequest;
import org.dtb.servers.jwt.JWTParser;
import org.dtb.servers.routing.RequestRoute;
import org.dtb.servers.routing.RequestRoutingContexts;
import org.dtb.servers.routing.RequestRoutingResponse;
import org.dtb.servers.routing.RouteMessage;

import java.nio.charset.StandardCharsets;
import java.rmi.ServerException;
import java.util.function.Function;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpHeaderValues.TEXT_PLAIN;

public class HttpServerHandler extends SimpleChannelInboundHandler<HttpObject> {

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    private RequestRoutingResponse withAuthInfo(HttpRequest req, Function<AuthRequest.AuthInfo,RequestRoutingResponse> authInfoConsumer) throws SecurityException {
        AuthRequest.AuthInfo authInfo = null;
        RequestRoute route = getRoute(req);
        if(route==null){
           return RequestRoutingResponse.response(HttpResponseStatus.NOT_FOUND, new RouteMessage.RouteErrorMessage("missing resource "+req.uri()) );
        }
        if (getRoute(req).isAuthNeeded()) {
            String token = getToken(req);
            JWTParser parser = RequestRoutingContexts.getInstance().getParser();
            if(parser==null){
                return RequestRoutingResponse.response(HttpResponseStatus.UNAUTHORIZED, new RouteMessage.RouteErrorMessage("missing auth token parser configuration") );
            }
            authInfo=toAuthInfo(token);
        }
        return authInfoConsumer.apply(authInfo);
    }

    private AuthRequest.AuthInfo toAuthInfo(String token) throws SecurityException {
        try {
            AuthRequest.AuthInfo authInfo = RequestRoutingContexts.getInstance().getParser().verify(token);
            RequestRoutingContexts.getInstance().setAuthInfo(authInfo);
            return authInfo;
        }catch (Exception e){
            throw new SecurityException("invalid auth token",HttpResponseStatus.FORBIDDEN);
        }
    }
    private RequestRoute getRoute(HttpRequest req){
        return RequestRoutingContexts.getInstance().getRouter(req.uri());
    }

    private String getToken(HttpRequest req) throws SecurityException {
        String token = req.headers().get("Authorization");
        if (token != null && RequestRoutingContexts.getInstance().getParser() != null && (token.contains("Bearer"))) {
                token = token.substring(7);
        }
        if(token==null){
            throw new SecurityException("missing auth token",HttpResponseStatus.FORBIDDEN);
        }
        return token;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
        if (msg instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) msg;
            RequestRoute route = RequestRoutingContexts.getInstance().getRouter(req.uri());
            RequestRoutingResponse response;
            try {
                response = withAuthInfo(req,authInfo->{
                    try {
                        return route.handle(req);
                    } catch (Exception e) {
                        return RequestRoutingResponse.response(HttpResponseStatus.INTERNAL_SERVER_ERROR, new RouteMessage.RouteErrorMessage(e.getMessage()));
                    }
                });
            } catch (SecurityException e) {
                response = RequestRoutingResponse.response(e.getStatus(), new RouteMessage.RouteErrorMessage(e.getMessage()));
            }
            handleResponse(ctx, req, response);
        }
    }

    private void handleResponse(ChannelHandlerContext ctx, HttpRequest req, RequestRoutingResponse routeResponse) {
        boolean keepAlive = HttpUtil.isKeepAlive(req);
        FullHttpResponse response = new DefaultFullHttpResponse(req.protocolVersion(), routeResponse.getStatus(),
                Unpooled.wrappedBuffer(routeResponse.getBody().getBytes(StandardCharsets.UTF_8)));
        response.headers()
                .set(CONTENT_TYPE, TEXT_PLAIN)
                .setInt(CONTENT_LENGTH, response.content().readableBytes());
        if (keepAlive) {
            if (!req.protocolVersion().isKeepAliveDefault()) {
                response.headers().set(CONNECTION, KEEP_ALIVE);
            }
        } else {
            response.headers().set(CONNECTION, CLOSE);
        }

        ChannelFuture f = ctx.write(response);

        if (!keepAlive) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}