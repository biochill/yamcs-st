package org.yamcs.web.websocket;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.management.LinkListener;
import org.yamcs.management.ManagementService;
import org.yamcs.protobuf.Web.WebSocketServerMessage.WebSocketReplyData;
import org.yamcs.protobuf.Yamcs.ProtoDataType;
import org.yamcs.protobuf.YamcsManagement.LinkEvent;
import org.yamcs.protobuf.YamcsManagement.LinkInfo;

/**
 * Provides realtime data-link subscription via web.
 */
public class LinkResource extends AbstractWebSocketResource implements LinkListener {

    private static final Logger log = LoggerFactory.getLogger(LinkResource.class);
    public static final String RESOURCE_NAME = "links";

    public static final String OP_subscribe = "subscribe";
    public static final String OP_unsubscribe = "unsubscribe";

    public LinkResource(WebSocketProcessorClient client) {
        super(client);
    }

    @Override
    public WebSocketReplyData processRequest(WebSocketDecodeContext ctx, WebSocketDecoder decoder)
            throws WebSocketException {
        switch (ctx.getOperation()) {
        case OP_subscribe:
            return subscribe(ctx.getRequestId());
        case OP_unsubscribe:
            return unsubscribe(ctx.getRequestId());
        default:
            throw new WebSocketException(ctx.getRequestId(), "Unsupported operation '" + ctx.getOperation() + "'");
        }
    }

    private WebSocketReplyData subscribe(int requestId) throws WebSocketException {
        ManagementService mservice = ManagementService.getInstance();

        try {
            WebSocketReplyData reply = toAckReply(requestId);
            wsHandler.sendReply(reply);

            for (LinkInfo linkInfo : mservice.getLinkInfo()) {
                sendLinkInfo(LinkEvent.Type.REGISTERED, linkInfo);
            }
            mservice.addLinkListener(this);
            return null;
        } catch (IOException e) {
            log.error("Exception when sending data", e);
            return null;
        }
    }

    private WebSocketReplyData unsubscribe(int requestId) throws WebSocketException {
        ManagementService mservice = ManagementService.getInstance();
        mservice.removeLinkListener(this);
        return toAckReply(requestId);
    }

    @Override
    public void quit() {
        ManagementService mservice = ManagementService.getInstance();
        mservice.removeLinkListener(this);
    }

    @Override
    public void linkRegistered(LinkInfo linkInfo) {
        sendLinkInfo(LinkEvent.Type.REGISTERED, linkInfo);
    }

    @Override
    public void linkUnregistered(LinkInfo linkInfo) {
        // TODO Currently not handled correctly by ManagementService

    }

    @Override
    public void linkChanged(LinkInfo linkInfo) {
        sendLinkInfo(LinkEvent.Type.UPDATED, linkInfo);
    }

    private void sendLinkInfo(LinkEvent.Type type, LinkInfo linkInfo) {
        try {
            LinkEvent.Builder linkb = LinkEvent.newBuilder();
            linkb.setType(type);
            linkb.setLinkInfo(linkInfo);
            wsHandler.sendData(ProtoDataType.LINK_EVENT, linkb.build());
        } catch (Exception e) {
            log.warn("got error when sending link event, quitting", e);
            quit();
        }
    }
}
