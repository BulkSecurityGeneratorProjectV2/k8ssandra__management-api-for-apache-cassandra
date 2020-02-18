package org.apache.cassandra.transport;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.cassandra.auth.IAuthenticator;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.ClientWarn;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.transport.messages.ErrorMessage;
import org.apache.cassandra.utils.JVMStabilityInspector;

public class UnixSocketServer
{
    private static final Logger logger = LoggerFactory.getLogger(UnixSocketServer.class);

    public static ChannelInitializer<Channel> makeSocketInitializer(final Server.ConnectionTracker connectionTracker)
    {
        // Stateless handlers
        final Message.ProtocolDecoder messageDecoder = new Message.ProtocolDecoder();
        final Message.ProtocolEncoder messageEncoder = new Message.ProtocolEncoder(ProtocolVersionLimit.SERVER_DEFAULT);
        final Frame.Decompressor frameDecompressor = new Frame.Decompressor();
        final Frame.Compressor frameCompressor = new Frame.Compressor();
        final Frame.Encoder frameEncoder = new Frame.Encoder();
        final Message.ExceptionHandler exceptionHandler = new Message.ExceptionHandler();
        final UnixSockMessage dispatcher = new UnixSockMessage();

        return new ChannelInitializer<Channel>()
        {
            @Override
            protected void initChannel(Channel channel) throws Exception
            {
                ChannelPipeline pipeline = channel.pipeline();

                pipeline.addLast("frameDecoder", new Frame.Decoder((channel1, version) ->
                       new UnixSocketConnection(channel1, version, connectionTracker), ProtocolVersionLimit.SERVER_DEFAULT));
                pipeline.addLast("frameEncoder", frameEncoder);

                pipeline.addLast("frameDecompressor", frameDecompressor);
                pipeline.addLast("frameCompressor", frameCompressor);

                pipeline.addLast("messageDecoder", messageDecoder);
                pipeline.addLast("messageEncoder", messageEncoder);

                pipeline.addLast("executor", dispatcher);

                // The exceptionHandler will take care of handling exceptionCaught(...) events while still running
                // on the same EventLoop as all previous added handlers in the pipeline. This is important as the used
                // eventExecutorGroup may not enforce strict ordering for channel events.
                // As the exceptionHandler runs in the EventLoop as the previous handlers we are sure all exceptions are
                // correctly handled before the handler itself is removed.
                // See https://issues.apache.org/jira/browse/CASSANDRA-13649
                pipeline.addLast("exceptionHandler", exceptionHandler);
            }
        };
    }

    @ChannelHandler.Sharable
    static class UnixSockMessage extends SimpleChannelInboundHandler<Message.Request>
    {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Message.Request request) throws Exception
        {
            final Message.Response response;
            final UnixSocketConnection connection;
            long queryStartNanoTime = System.nanoTime();

            try
            {
                assert request.connection() instanceof UnixSocketConnection;
                connection = (UnixSocketConnection) request.connection();
                if (connection.getVersion().isGreaterOrEqualTo(ProtocolVersion.V4))
                    ClientWarn.instance.captureWarnings();

                QueryState qstate = connection.validateNewMessage(request.type, connection.getVersion(), request.getStreamId());
                //logger.info("Executing {} {} {}", request, connection.getVersion(), request.getStreamId());

                response = request.execute(qstate, queryStartNanoTime);
                response.setStreamId(request.getStreamId());
                response.setWarnings(ClientWarn.instance.getWarnings());
                response.attach(connection);
                connection.applyStateTransition(request.type, response.type);
            }
            catch (Throwable t)
            {
                //logger.warn("Exception encountered", t);
                JVMStabilityInspector.inspectThrowable(t);
                Message.UnexpectedChannelExceptionHandler handler = new Message.UnexpectedChannelExceptionHandler(ctx.channel(), true);
                ctx.writeAndFlush(ErrorMessage.fromException(t, handler).setStreamId(request.getStreamId()));
                request.getSourceFrame().release();
                return;
            }
            finally
            {
                ClientWarn.instance.resetWarnings();
            }

            ctx.writeAndFlush(response);
            request.getSourceFrame().release();
        }
    }

    static class UnixSocketConnection extends ServerConnection
    {
        private enum State { UNINITIALIZED, AUTHENTICATION, READY }

        private final ClientState clientState;
        private volatile State state;
        private final ConcurrentMap<Integer, QueryState> queryStates = new ConcurrentHashMap<>();

        public UnixSocketConnection(Channel channel, ProtocolVersion version, Connection.Tracker tracker)
        {
            super(channel, version, tracker);
            this.clientState = ClientState.forInternalCalls();
            this.state = State.UNINITIALIZED;
        }

        private QueryState getQueryState(int streamId)
        {
            QueryState qState = queryStates.get(streamId);
            if (qState == null)
            {
                // In theory we shouldn't get any race here, but it never hurts to be careful
                QueryState newState = new QueryState(clientState);
                if ((qState = queryStates.putIfAbsent(streamId, newState)) == null)
                    qState = newState;
            }
            return qState;
        }

        @Override
        public QueryState validateNewMessage(Message.Type type, ProtocolVersion version, int streamId)
        {
            switch (state)
            {
                case UNINITIALIZED:
                    if (type != Message.Type.STARTUP && type != Message.Type.OPTIONS)
                        throw new ProtocolException(String.format("Unexpected message %s, expecting STARTUP or OPTIONS", type));
                    break;
                case AUTHENTICATION:
                    // Support both SASL auth from protocol v2 and the older style Credentials auth from v1
                    if (type != Message.Type.AUTH_RESPONSE && type != Message.Type.CREDENTIALS)
                        throw new ProtocolException(String.format("Unexpected message %s, expecting %s", type, version == ProtocolVersion.V1 ? "CREDENTIALS" : "SASL_RESPONSE"));
                    break;
                case READY:
                    if (type == Message.Type.STARTUP)
                        throw new ProtocolException("Unexpected message STARTUP, the connection is already initialized");
                    break;
                default:
                    throw new AssertionError();
            }
            return getQueryState(streamId);
        }

        @Override
        public void applyStateTransition(Message.Type requestType, Message.Type responseType)
        {
            switch (state)
            {
                case UNINITIALIZED:
                    if (requestType == Message.Type.STARTUP)
                    {
                        if (responseType == Message.Type.AUTHENTICATE)
                            state = State.AUTHENTICATION;
                        else if (responseType == Message.Type.READY)
                            state = State.READY;
                    }
                    break;
                case AUTHENTICATION:
                    // Support both SASL auth from protocol v2 and the older style Credentials auth from v1
                    assert requestType == Message.Type.AUTH_RESPONSE || requestType == Message.Type.CREDENTIALS;

                    if (responseType == Message.Type.READY || responseType == Message.Type.AUTH_SUCCESS)
                    {
                        state = State.READY;
                        // we won't use the authenticator again, null it so that it can be GC'd
                    }
                    break;
                case READY:
                    break;
                default:
                    throw new AssertionError();
            }
        }

        public IAuthenticator.SaslNegotiator getSaslNegotiator(QueryState queryState)
        {
            return null;
        }
    }
}
